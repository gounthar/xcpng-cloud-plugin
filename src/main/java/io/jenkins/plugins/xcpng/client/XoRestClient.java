package io.jenkins.plugins.xcpng.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * {@link HypervisorClient} over Xen Orchestra's REST API, the second backend beside {@link XapiClient}
 * (#89 step 1). XAPI remains the default; this exists so the XO path can be exercised against a real pool
 * while the plugin still works, and step 3 flips the default and removes the XAPI client.
 *
 * <p>What changes relative to the XAPI backend, all of it measured on the lab appliance or read from
 * {@code vatesfr/xen-orchestra} rather than assumed:
 *
 * <ul>
 *   <li><b>Do not pass {@code vifs} when creating.</b> {@code create_vm} over REST inherits the
 *       template's VIFs, and passing them is <em>additive</em>: handing the route the template's own
 *       network produced a clone with two NICs rather than an error. (JSON-RPC {@code vm.create} is the
 *       opposite and needs them passed, which is exactly the code that gets broken by porting a call from
 *       one to the other.) A VIF-less clone is the worst shape this project knows, because it boots:
 *       guest tools report healthy on every field and the only one telling the truth is the address that
 *       never arrives.
 *   <li><b>The owner marker is a tag.</b> {@code XoVm} carries no {@code other_config} and neither does
 *       the PATCH body type, so {@link OwnerMarker#OWNER_KEY} has no {@code other_config} key to sit in here. The equivalent is
 *       {@code PUT /vms/{id}/tags/{tag}}, which bottoms out on XAPI {@code add_tags}. Tags are visible in
 *       the XO UI where {@code other_config} effectively is not, so an operator will see
 *       {@code xcpng-cloud:<cloud>} on every clone. <b>While both backends exist, a sweep has to read
 *       both markers</b>, or a clone leaked by one is invisible to it: {@code tools/reaper.py} read
 *       only {@code other_config} until #231, and {@code tools/owner.py} now reads either.
 *   <li><b>Teardown captures no disks.</b> {@code DELETE /vms/{id}} deletes them unconditionally and
 *       offers no way to ask it not to, so the capture-before-destroy ordering this interface documents
 *       is XO's problem now rather than ours. It gets it right ({@code VM_getDisks} before
 *       {@code VM.destroy}). It also carries #48 verbatim: it gates {@code hard_shutdown} on
 *       {@code power_state !== 'Halted'}, the one field we have observed lying. Migrating does not retire
 *       that issue, it moves it into a codebase we cannot patch.
 *   <li><b>{@code primaryIpAddress} needs a link-local filter.</b> {@code mainIpAddress} is computed for
 *       us, so the {@code guest_metrics} round trip and the stale-husk check go away -- but it can carry
 *       an address nothing can connect to. Measured n=3 and not deterministic: {@code fe80::} at 25.3s;
 *       None until 85.1s then IPv4; {@code fe80::} for about 15s then IPv4 at 85.4s. A caller taking the
 *       first non-empty value gets the link-local.
 * </ul>
 *
 * <p>Not thread-safe by the same rule as {@link XapiClient}: one client per operation.
 */
public final class XoRestClient implements HypervisorClient {

    private static final Logger LOGGER = Logger.getLogger(XoRestClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API = "/rest/v0";

    /** A VM id no VM can have, so {@link #probePatchRoute()} can address the route and touch nothing. */
    static final String PROBE_ID = "00000000-0000-0000-0000-000000000000";

    /**
     * Reads answer in well under a second; the lifecycle verbs are minutes. Two constants rather than one
     * because a single timeout that suits both is either too short to clone or too long to notice an
     * appliance that has gone away. The long one matches {@link XapiClient}'s task deadline, so a slow
     * clone fails at the same point on both backends.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration ACTION_TIMEOUT = Duration.ofMinutes(15);

    /**
     * Tag prefix stamped on every clone this plugin provisions, with the owning cloud's name after it. The
     * tag-shaped form of {@link OwnerMarker#OWNER_KEY}, and deliberately built from that constant: the
     * two markers have to be recognisable as the same thing by whatever sweeps for either.
     *
     * <p>Inheritable, exactly as the XAPI marker is: {@code VM.clone} copies {@code tags} as well as
     * {@code other_config}, so a hand-made clone of a marked agent carries this tag too. {@link
     * #SELF_TAG_PREFIX} is what separates a clone this plugin made from a copy of one.
     */
    public static final String OWNER_TAG_PREFIX = OwnerMarker.OWNER_KEY + ":";

    /**
     * Tag prefix holding the uuid of the VM the record belongs to, stamped beside the owner tag. The XO-side
     * tag-shaped form of {@link OwnerMarker#SELF_KEY}, built from that constant for the same reason, and note it
     * does not collide with {@link #OWNER_TAG_PREFIX}: {@code xcpng-cloud-uuid:} does not start with
     * {@code xcpng-cloud:}, so a sweep reading owner tags never mistakes one for the other.
     *
     * <p>Unlike the XAPI side, which writes both keys in one {@code set_other_config}, tags go in one PUT
     * each, so a clone can exist carrying the owner tag and not yet this one. That ordering is deliberate
     * and is the safe direction: such a survivor reads as an older-plugin clone and stays reapable, whereas
     * stamping the uuid first would leave a survivor carrying neither an owner tag nor anything a sweep
     * selects on.
     */
    public static final String SELF_TAG_PREFIX = OwnerMarker.SELF_KEY + ":";

    /** Xenstore path the guest agent reads its seed from. Only {@code vm-data/*} keys reach the guest. */
    private static final String GUEST_DATA_PREFIX = "vm-data/jenkins/";

    private final RestTransport transport;

    /**
     * @param baseUrl base URL of the appliance, e.g. {@code https://192.168.1.5}
     * @param token XO authentication token (a credential the plugin resolves at point of use, never
     *     stored here)
     * @param certificateFingerprint SHA-256 fingerprint of the certificate the appliance is expected to
     *     present. Null or blank means ordinary verification against the JVM trust store.
     */
    public XoRestClient(@NonNull String baseUrl, @NonNull String token, @CheckForNull String certificateFingerprint) {
        this(new HttpRestTransport(baseUrl, token, certificateFingerprint));
        // The form validator rejects http, but it is advisory: a JCasC document or a hand-edited
        // config.xml can still persist an http base URL. Warn here so the cleartext exposure is not
        // silent. It matters more than it does for XAPI: the token is sent on every single request as a
        // cookie, so one plaintext round trip hands it over, and it does not expire on its own.
        if (baseUrl.regionMatches(true, 0, "http://", 0, "http://".length())) {
            LOGGER.warning("Xen Orchestra URL " + baseUrl + " uses plain http; the authentication token is"
                    + " sent in cleartext on every request, and the pinned certificate is not consulted."
                    + " Use https://.");
        }
    }

    /** For tests: inject a transport that replays recorded responses. */
    XoRestClient(@NonNull RestTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    // -- plumbing ---------------------------------------------------------

    /**
     * One call, with the failure envelope turned into a {@link HypervisorException} that carries XO's own
     * error code structurally.
     *
     * <p>XO's failure body is {@code {"error": <message>, "data"?: {...}, "info"?: ...}} (read from
     * {@code generic-error-handler.middleware.mts}). When the underlying failure came from XAPI rather
     * than from XO, {@code error} <em>is</em> the XAPI code -- {@code HANDLE_INVALID} and the rest -- and
     * {@code info} says so, which is what lets a caller branch on a code on this backend too.
     */
    @NonNull
    private JsonNode call(String method, String path, @CheckForNull ObjectNode body, Duration timeout) {
        return call(method, path, body, timeout, null);
    }

    /**
     * As above, with a sentence naming what a refused 202 may have left behind.
     *
     * <p>Only the create call passes one. Everywhere else a refused 202 leaves nothing to clean up, and a
     * consequence that does not apply is worse than none.
     */
    @NonNull
    private JsonNode call(
            String method,
            String path,
            @CheckForNull ObjectNode body,
            Duration timeout,
            @CheckForNull String consequence) {
        RestTransport.RestResponse resp;
        try {
            resp = transport.send(method, path, body == null ? null : body.toString(), timeout);
        } catch (IOException e) {
            throw new HypervisorException(method + " " + path + ": transport error: " + e.getMessage(), e);
        }
        // Status first, body second. A failure body is not always JSON -- a reverse proxy's 502 page and a
        // login redirect's HTML both arrive here -- and parsing before branching turned those into
        // "malformed response", which reads as our bug and drops the one fact the operator needs. Only a
        // 2xx is required to be JSON.
        if (!resp.isSuccess()) {
            throw failure(method, path, resp.status(), resp.body());
        }
        refuseAsyncAccepted(method, path, resp, consequence);
        return parse(method + " " + path, resp.body());
    }

    /**
     * Refuse a 202, which is XO saying it started a task rather than doing the work.
     *
     * <p>Every lifecycle call here asks for {@code ?sync=true}, but that is a request and not a guarantee:
     * an appliance that does not know the parameter ignores it, the way any HTTP API ignores an unknown
     * query parameter, and the 6.5.0 floor this backend needs lives in {@code XcpngBackend}'s javadoc
     * rather than in a runtime check. {@code RestResponse.isSuccess} is {@code status / 100 == 2}, so a
     * 202 was passing as a synchronous answer.
     *
     * <p>What that costs is worth spelling out, because it is silent. {@code cloneFromTemplate} reads
     * {@code id} off the body, so a 202 carrying a <em>task</em> id makes the task id the VM ref. The
     * sizing PATCH then 404s against it, the cleanup DELETE addresses the task, and the clone that was
     * really created is left running with no owner tag and no ref recorded anywhere. The existing test
     * asserting {@code sync=true} is on the URL pins the request and says nothing about the response.
     *
     * <p>Refusing beats waiting on the task. Teaching this client XO's task-polling protocol would be a
     * second code path exercised only against appliances the backend does not claim to support, and an
     * operator is better served by being told their appliance is too old than by the plugin quietly
     * working one way here and another way there.
     */
    private static void refuseAsyncAccepted(
            String method, String path, RestTransport.RestResponse resp, @CheckForNull String consequence) {
        if (resp.status() != 202) {
            return;
        }
        throw new HypervisorException(method + " " + path + ": the appliance answered 202 Accepted, so it started"
                + " a background task instead of doing the work. This backend asks every call for"
                + " ?sync=true and needs Xen Orchestra 6.5.0 or newer; an older appliance ignores the"
                + " parameter. Upgrade Xen Orchestra, or use the XAPI backend."
                + (consequence == null ? "" : " " + consequence));
    }

    @NonNull
    private JsonNode get(String path) {
        return call("GET", path, null, READ_TIMEOUT);
    }

    /**
     * A response body as JSON, or a missing node when there was no body.
     *
     * <p>A 204 carries none, and both the seed PATCH and the tag PUT answer 204, so this must not treat an
     * empty body as malformed. A bare JSON scalar ({@code 5}, {@code null}, {@code true}) is a valid
     * document and is not an object; it is returned as-is and the callers that need a field ask through
     * {@code path()}, which answers a missing node rather than throwing. Same guard {@code tools/xapi.py}
     * and {@code tools/xo_rest.py} both had to learn.
     */
    @NonNull
    private static JsonNode parse(String what, String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.missingNode();
        }
        try {
            JsonNode parsed = MAPPER.readTree(body);
            return parsed == null ? MAPPER.missingNode() : parsed;
        } catch (IOException e) {
            // A proxy error page or a login redirect is HTML, not JSON. Keep the first of it in the
            // message so the operator sees what answered rather than a bare parse error.
            throw new HypervisorException(
                    what + ": malformed response: " + e.getMessage() + ": "
                            + body.substring(0, Math.min(body.length(), 200)),
                    e);
        }
    }

    /**
     * A failure envelope, or whatever the far end sent instead of one.
     *
     * <p>Takes the raw body rather than a parsed node, because the body of a failure is exactly where
     * non-JSON turns up. An unparseable one is reported as an excerpt beside the status, which is what an
     * operator needs to tell a proxy in the way from an appliance that said no.
     */
    @NonNull
    private static HypervisorException failure(String method, String path, int status, String body) {
        JsonNode payload;
        try {
            // Any valid JSON is kept, object or not. The "collapse a non-object" guard that was here is a
            // port of tools/xapi.py's, where `"error" in payload` raises TypeError on a bare scalar. Java
            // does not need it: JsonNode.path answers a missing node for a field on an array or a scalar.
            // It cost the diagnostic instead, turning a 502 body of ["Bad Gateway"] into "HTTP 502: {}".
            JsonNode read = MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
            payload = read == null ? MAPPER.createObjectNode() : read;
        } catch (IOException e) {
            // The hint belongs here too, and this is the branch that needs it most: a 401 whose body is
            // HTML is the login-redirect case, where the excerpt on its own tells an operator nothing.
            String excerpt = body == null ? "" : body.substring(0, Math.min(body.length(), 200));
            return new HypervisorException(
                    method + " " + path + ": HTTP " + status + ": " + excerpt + hintFor(method, path, status, body));
        }
        String error = payload.path("error").asText("");
        String code = error.isBlank() ? null : error;
        String detail = error.isBlank() ? payload.toString() : error;
        List<String> params = new ArrayList<>();
        JsonNode data = payload.path("data");
        if (data.isArray()) {
            for (JsonNode element : data) {
                params.add(element.asText());
            }
        } else if (data.isObject()) {
            data.fields()
                    .forEachRemaining(
                            e -> params.add(e.getKey() + "=" + e.getValue().asText()));
        }
        return new HypervisorException(
                method + " " + path + ": HTTP " + status + ": " + detail + hintFor(method, path, status, body),
                code,
                params);
    }

    /**
     * What an authentication or authorisation status means on this backend, appended to the failure it
     * explains.
     *
     * <p>This is where the two backends are least alike, and the XO side was the poorer of the two. XAPI
     * answers a bad credential with a named code ({@code SESSION_AUTHENTICATION_FAILED}) and re-logs in by
     * itself when a session merely went stale, so neither case reaches an operator as a bare number. XO has
     * no session to refresh -- the token is the credential -- and it answers every one of its distinct
     * causes with the same status and, per {@link HttpRestTransport}'s own note, the same body. An operator
     * reading "HTTP 401" off the Test Connection button has no way to tell a revoked token from a token
     * sent as {@code Authorization: Bearer} instead of as a cookie.
     *
     * <p>403 is worth its own sentence because it is not an authentication problem at all and reads like
     * one. Measured on the lab appliance: at plan 1 the ACL routes answer 403 while every route this client
     * uses answers 200, with the same token. So a 403 here points at the account's plan or role, never at
     * the token being wrong.
     *
     * <p>404 gets a hint only when the body is not XO's own envelope, because the two 404s mean opposite
     * things. Measured on the lab appliance (xo-server 5.208.3) 2026-09-25: a routed {@code PATCH} on an
     * unknown id answers JSON, {@code {"error":"no such VM ..."}}, while a method the appliance does not
     * route ({@code PUT} on the same path) answers an HTML page reading {@code Cannot PUT ...}. The first is
     * about the object and needs no help. The second is the appliance not serving the route at all, which is
     * what an xo-server too old for this backend looks like: {@code PATCH /vms/{id}} answered 404 on 5.192.1
     * and 204 on 5.208.3 (#254). Without the hint that reaches an operator as a bare 404 at the first
     * provision, naming nothing.
     *
     * <p>The hint is appended rather than substituted. XO's own message is the more specific of the two
     * whenever it says anything, and dropping it to print our guess would be the worse trade.
     */
    @NonNull
    private static String hintFor(String method, String path, int status, @CheckForNull String body) {
        if (status == 404 && !isNoSuchObject(body, null)) {
            return routeMissingHint(method, path);
        }
        return switch (status) {
            case 401 ->
                ". The appliance did not accept the token. It may be wrong, revoked or expired;"
                        + " note that XO expires tokens and does not renew them. A token that is otherwise"
                        + " valid also reads as 401 if it is sent as an Authorization header rather than as"
                        + " the authenticationToken cookie, which this client sends.";
            case 403 ->
                ". The token authenticated but is not allowed this route, so this is the account's"
                        + " role or the appliance's plan rather than the credential.";
            default -> "";
        };
    }

    /**
     * The 404 that is not XO's: the appliance, or something in front of it, does not serve this route.
     *
     * <p>The version sentence is limited to the one route whose absence has been measured, rather than
     * attached to every unrouted call, so it never claims a floor for a route nobody has checked.
     */
    @NonNull
    private static String routeMissingHint(String method, String path) {
        String hint = ". The appliance does not serve " + method + " on this route: the reply is not XO's own,"
                + " which would name a missing object. Either xo-server is too old for this backend, or a"
                + " proxy in front of it does not forward " + API + ".";
        if ("PATCH".equals(method) && path.startsWith(API + "/vms/")) {
            hint += " PATCH " + API + "/vms/{id} is absent on xo-server 5.192.1 and present on 5.208.3;"
                    + " upgrading an XOA needs it to be registered.";
        }
        return hint;
    }

    /**
     * Percent-encode one path segment, slash included.
     *
     * <p>The tag carries an operator-supplied cloud name, and a space or a slash in that name walks
     * straight into the URL: a slash makes the request address a different route entirely, which answers
     * something rather than erroring, and a sweep then finds no tag on a VM the plugin believes it
     * tagged. Object ids come from XO rather than from an operator and are single segments, so they are
     * deliberately not passed through here -- encoding operator input and leaving server output alone is
     * the line, and it is worth keeping visible.
     */
    @NonNull
    static String encodeSegment(@NonNull String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return out.toString();
    }

    // -- template handles -------------------------------------------------

    /**
     * How a template's handle is spelled on this backend: {@code <poolId>/<bare template uuid>}.
     *
     * <p>Both halves are needed and neither route will take the other's form. Creation hangs off
     * <b>/pools</b>, not /vms, so the pool id is not optional; and that route wants a <b>bare</b> uuid,
     * where the pool-prefixed id XO hands you nearly everywhere else answers 404 "no such object", which
     * reads exactly like the capability being missing. That mistake has been made twice on this project,
     * once in the tools and once in the issue that concluded REST could not instantiate a template at all.
     *
     * <p>{@link VmRef} is documented as opaque precisely so a backend can do this; nothing outside this
     * class parses it.
     */
    private record TemplateHandle(String poolId, String templateUuid) {

        static TemplateHandle parse(VmRef ref) {
            int slash = ref.value().indexOf('/');
            if (slash <= 0 || slash == ref.value().length() - 1) {
                throw new HypervisorException("not a template handle from this backend: " + ref.value()
                        + ". Templates must be resolved through resolveTemplate on the same client;"
                        + " a handle from the XAPI backend cannot be used here.");
            }
            return new TemplateHandle(
                    ref.value().substring(0, slash), ref.value().substring(slash + 1));
        }

        VmRef toRef() {
            return new VmRef(poolId + "/" + templateUuid);
        }
    }

    // -- the verbs --------------------------------------------------------

    @Override
    @NonNull
    public VmRef resolveTemplate(@NonNull String name) {
        // Listed and matched here rather than pushed into the route's `filter=` parameter. That parameter
        // takes complex-matcher syntax, and a mistyped filter matches nothing and is indistinguishable
        // from the template being absent -- the exact failure shape this project keeps paying for. A pool
        // holds a few dozen templates, so listing them costs one request either way.
        JsonNode templates = get(API + "/vm-templates?fields=id,uuid,name_label,$pool");
        if (!templates.isArray()) {
            throw new HypervisorException(
                    "GET " + API + "/vm-templates: expected a list of templates, got " + templates.getNodeType());
        }
        List<TemplateHandle> matches = new ArrayList<>();
        for (JsonNode template : templates) {
            if (!name.equals(template.path("name_label").asText(null))) {
                continue;
            }
            String uuid = template.path("uuid").asText("");
            String pool = template.path("$pool").asText("");
            if (uuid.isBlank() || pool.isBlank()) {
                // Refuse rather than fall back to `id`: the pool-prefixed id is the value that answers 404
                // on the create route, so a silent substitution here is the twice-made mistake all over
                // again, arriving as a missing capability at clone time instead of as a bad read now.
                throw new HypervisorException("template '" + name + "' reports no uuid or no pool (" + template
                        + "); this appliance is not answering the shape this backend needs");
            }
            matches.add(new TemplateHandle(pool, uuid));
        }
        if (matches.isEmpty()) {
            throw new HypervisorException("no template named '" + name + "' on this appliance" + " (" + templates.size()
                    + " template(s) visible to this token)");
        }
        if (matches.size() > 1) {
            // First-match would be non-deterministic and could clone the wrong image.
            throw new HypervisorException(ambiguousMessage(name, matches));
        }
        return matches.get(0).toRef();
    }

    /**
     * Why a template name matched more than once, in terms an operator can act on.
     *
     * <p>Two different situations reach this line and the message used to give one answer to both.
     * Several templates in <em>one</em> pool really are duplicates, and renaming one of them is the fix.
     * The same name in <em>two</em> pools is not a mistake at all: it is the ordinary outcome of building
     * a golden image twice from the same Packer recipe, and that estate provisions on {@link XapiClient},
     * which is connected to a single pool master and so never sees the second copy. Only this backend
     * refuses it, because one XO appliance fronts every pool its token can see. Telling that operator to
     * rename a correctly named image sends them to fix the wrong thing.
     *
     * <p>So the pools are named -- {@link TemplateHandle} already carries the id -- and the advice is
     * assembled from the two facts that are actually true of this estate rather than picked from a
     * branch. A mixed case (two in one pool, one in another) gets both halves, which is the shape a
     * three-way branch would have quietly dropped.
     *
     * <p>Choosing the pool is issue #241's larger half and is deliberately not done here; the message
     * says what the operator can do today instead of naming an option that does not exist yet.
     */
    @NonNull
    private static String ambiguousMessage(@NonNull String name, @NonNull List<TemplateHandle> matches) {
        Map<String, Integer> byPool = new LinkedHashMap<>();
        for (TemplateHandle match : matches) {
            byPool.merge(match.poolId(), 1, Integer::sum);
        }
        StringBuilder where = new StringBuilder();
        byPool.forEach((pool, count) -> {
            if (where.length() > 0) {
                where.append(", ");
            }
            where.append(count).append(" in pool ").append(pool);
        });
        List<String> advice = new ArrayList<>();
        if (byPool.size() < matches.size()) {
            advice.add("at least one pool holds more than one, so rename those until the name is unique"
                    + " within its pool");
        }
        if (byPool.size() > 1) {
            advice.add("the name is also carried by more than one pool, and this backend cannot yet be"
                    + " told which pool to provision into: it lists every template the token can see,"
                    + " unlike the XAPI backend, which is connected to one pool master. Until a cloud can"
                    + " name its pool, scope the token to a single pool or give the images distinct names");
        }
        return matches.size() + " templates are named '" + name + "' (" + where + "); " + String.join("; ", advice);
    }

    @Override
    @NonNull
    public VmRef cloneFromTemplate(@NonNull VmRef template, @NonNull ProvisionSpec spec) {
        if (spec.placementHint() != null) {
            // Rejected before anything is created, so a bad spec fails with this local message rather than
            // as a connection or auth error, and leaves no VM behind. Same rule as the XAPI backend:
            // reject rather than silently ignore, so a caller cannot believe it placed a VM when it did not.
            throw new HypervisorException("placementHint is not honoured in v0 (single-host pool); leave it null");
        }
        if (spec.diskBytes() != null) {
            // A gap in this backend rather than in the interface, and said out loud for the same reason.
            // The XO REST surface has no VDI resize route at all; the way to grow a clone's root disk is
            // to pass `vdis: [{userdevice, size}]` to the create route, which needs the template's root
            // userdevice read first and refuses to shrink. The plugin passes null here on every path, so
            // this is unreached today; wiring it blind and untested would be worse than refusing it.
            throw new HypervisorException("diskBytes is not honoured by the Xen Orchestra backend yet;"
                    + " size the golden image's disk instead, or use the XAPI backend");
        }
        TemplateHandle handle = TemplateHandle.parse(template);

        ObjectNode create = MAPPER.createObjectNode();
        create.put("name_label", spec.name());
        create.put("template", handle.templateUuid());
        create.put("clone", true); // copy-on-write; bottoms out on VM.clone, the 0.56s call
        create.put("boot", false); // the caller starts it, after the seed is written
        // Deliberately no "vifs". See the class javadoc: this route inherits the template's, and passing
        // them adds a second NIC.
        // The consequence matters only here. A refused 202 throws before any VM id comes back, so the
        // self-cleanup below never runs -- and XO may still finish the task, leaving a clone with no owner
        // tag. Jenkins retries provisioning, so that is one untagged VM per attempt, findable by nobody:
        // the sweeps select on the marker. The operator cannot act on that unless the message says it.
        JsonNode created = call(
                "POST",
                API + "/pools/" + handle.poolId() + "/actions/create_vm?sync=true",
                create,
                ACTION_TIMEOUT,
                "The task may still finish and create a VM named '" + spec.name() + "' carrying no owner tag,"
                        + " which no sweep will find; remove it by hand.");
        String vm = created.path("id").asText("");
        if (vm.isBlank()) {
            throw new HypervisorException("create_vm answered success but named no VM: " + created);
        }

        // The clone exists now, so anything past this point that throws would leave a VM and its disks
        // behind. Destroy it on any such failure before rethrowing, the same self-cleanup the XAPI backend
        // owes its own partial clones.
        try {
            configure(vm, spec);
        } catch (RuntimeException e) {
            // Clear the interrupt flag for the duration of the cleanup and restore it afterwards: a
            // teardown blocks on HTTP, which fails immediately on an already-interrupted thread, so an
            // interrupt mid-configure would otherwise leak the very clone this cleanup exists to reclaim.
            boolean interrupted = Thread.interrupted();
            try {
                destroyWithDisks(new VmRef(vm));
            } catch (RuntimeException cleanup) {
                // Best effort. The original failure is what the caller needs.
                LOGGER.warning("could not clean up partly configured clone " + vm + ": " + cleanup.getMessage());
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw e;
        }
        return new VmRef(vm);
    }

    /**
     * Stamp the owner tag, then size the clone and write its seed in one PATCH.
     *
     * <p><b>The tag goes first, and that is a trade rather than an accident.</b> It makes the tag PUT the
     * first thing that can fail once the clone exists, so a failure there now costs a provision that would
     * otherwise have succeeded. What it buys is that every clone surviving past this point carries the
     * marker, whenever the spec names an owner at all: a null or blank owner is untaggable by design and
     * {@code markOwner} returns early on it. Both backends destroy a partly configured clone on the way
     * out, so the ordinary failure is covered either way; the window this closes is the one where that
     * cleanup <em>also</em> fails -- an appliance blip, an interrupted thread, a 500 on the DELETE. A
     * survivor stamped last carries no tag, {@code provisionVm} has not recorded a ref for it either, and
     * both sweeps select on the marker, so nothing finds it but an operator's eye in the XO UI. The XAPI
     * backend already made this trade, and stamps second, right after the template flag.
     *
     * <p>Sizing goes through {@code PATCH /vms/{id}} rather than through the create body because that is
     * the surface with a declared type ({@code EditVmProps}); the create route's own body is the
     * loosely-typed rest of a JavaScript signature, and this project has already paid once for guessing at
     * what an untyped surface accepts.
     *
     * <p><b>{@code cpus} is sent and {@code cpusStaticMax} is not, and that is not an omission.</b> XAPI
     * enforces {@code 0 < VCPUs_at_startup <= VCPUs_max} on every write, which is why the XAPI backend
     * hand-orders the pair. XO's edit machinery already does that: {@code cpus} declares a
     * {@code cpusStaticMax: gte} constraint, and when the current max does not satisfy it the max is
     * raised first. Sending both instead would fire two setters <em>concurrently</em> ({@code
     * Promise.all} over the values), so shrinking would race a {@code VCPUs_max} write against a still
     * higher {@code VCPUs_at_startup} and XAPI would reject it.
     */
    private void configure(String vm, ProvisionSpec spec) {
        markOwner(vm, spec.owner());
        ObjectNode patch = MAPPER.createObjectNode();
        patch.put("cpus", spec.vcpus());
        patch.put("memory", spec.memoryBytes());
        if (!spec.guestData().isEmpty()) {
            ObjectNode xenstore = patch.putObject("xenStoreData");
            for (Map.Entry<String, String> e : spec.guestData().entrySet()) {
                xenstore.put(GUEST_DATA_PREFIX + e.getKey(), e.getValue());
            }
        }
        // One call, and it must be one call: the seed has to be in the VM record before the VM starts,
        // because setting xenstore-data on a running VM does not propagate to the guest.
        call("PATCH", API + "/vms/" + vm, patch, READ_TIMEOUT);
    }

    /**
     * Record the owning cloud, and this VM's own uuid, on the VM so an out-of-band sweep can find this clone
     * later and tell it from a copy of it. A per-tag PUT rather than the PATCH body's {@code tags} array,
     * which is a whole-list replace and would drop whatever the template carried.
     *
     * <p>The uuid is read back from the VM record rather than taken from {@code vm}, which is XO's object
     * id. The two are widely the same string on a XAPI-backed appliance, and this code does not depend on
     * that: the tools compare the stamp against the {@code uuid} field of the <em>XAPI</em> record, and an
     * id that turned out to be anything else would stamp a value that matches nothing and quietly refuse
     * every clone this backend makes. One GET buys not having to be right about it.
     *
     * <p>A clone whose source was itself marked arrives carrying an inherited uuid tag as well, because tags
     * are a set and the PUT adds rather than replaces. That is why the tools ask whether <em>any</em> stamp
     * matches rather than whether the only one does.
     *
     * <p><b>The read-back is immediate, measured rather than assumed.</b> The worry was that XO might answer
     * this GET before its object cache holds the clone, since the appliance is known to lag elsewhere:
     * {@code tools/xo_compare.py} polls for a tag it has just written rather than reading it once. Run
     * against a freshly deployed appliance on 2026-09-24, the GET issued straight after {@code create_vm}
     * answered <b>200 with the uuid on the first attempt, in 0.01s</b>, with a polling loop standing by that
     * was never needed. If a slower appliance ever does lag, the symptom is loud rather than silent, a
     * provision failing on the refusal below with the clone cleaned up, and the repair is to poll here the
     * way xo_compare.py polls for its tag.
     *
     * <p>On that appliance XO's object id and the XAPI uuid were in fact the same string. This code still
     * does not rely on it: one measurement on one appliance is not a guarantee about the id's provenance,
     * and the cost of reading the record is a single GET.
     */
    private void markOwner(String vm, @CheckForNull String owner) {
        if (owner == null || owner.isBlank()) {
            return;
        }
        call("PUT", API + "/vms/" + vm + "/tags/" + encodeSegment(OWNER_TAG_PREFIX + owner), null, READ_TIMEOUT);
        String uuid = get(API + "/vms/" + vm).path("uuid").asText("");
        if (uuid.isBlank()) {
            throw new HypervisorException("clone " + vm + " reports no uuid, so it cannot be stamped as this"
                    + " plugin's own; refusing rather than leaving a marker a hand-made copy would inherit");
        }
        call("PUT", API + "/vms/" + vm + "/tags/" + encodeSegment(SELF_TAG_PREFIX + uuid), null, READ_TIMEOUT);
    }

    @Override
    public void start(@NonNull VmRef vm) {
        call("POST", API + "/vms/" + vm.value() + "/actions/start?sync=true", null, ACTION_TIMEOUT);
    }

    @Override
    public void stop(@NonNull VmRef vm) {
        call("POST", API + "/vms/" + vm.value() + "/actions/clean_shutdown?sync=true", null, ACTION_TIMEOUT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A null value in {@code xenStoreData} is a per-key delete ({@code remove_from_xenstore_data}), so
     * this needs no read-merge-write and leaves url and name alone. An <em>omitted</em> key is a silent
     * no-op rather than a delete, which is why the null is written explicitly rather than the map being
     * rebuilt without it.
     */
    @Override
    public void clearGuestSecret(@NonNull VmRef vm) {
        ObjectNode patch = MAPPER.createObjectNode();
        patch.putObject("xenStoreData").putNull(GUEST_DATA_PREFIX + "secret");
        call("PATCH", API + "/vms/" + vm.value(), patch, READ_TIMEOUT);
    }

    @Override
    @NonNull
    public Optional<String> primaryIpAddress(@NonNull VmRef vm) {
        JsonNode read = get(API + "/vms/" + vm.value() + "?fields=power_state,mainIpAddress");
        if (!"Running".equals(read.path("power_state").asText(""))) {
            return Optional.empty();
        }
        String candidate = read.path("mainIpAddress").asText("");
        if (candidate.isBlank() || !isRoutable(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * Whether XO handed back an address a controller could actually be reached from.
     *
     * <p>Two ways this answers no, and both were observed: a value that is not an address at all, and a
     * link-local one. {@code mainIpAddress} carried {@code fe80::} at 25.3s on one clone and again for
     * about 15s on another before IPv4 arrived, so a caller taking the first non-empty value gets an
     * address nothing can connect to.
     *
     * <p>Decided by {@link InetAddress#isLinkLocalAddress()} rather than by a prefix test, because the
     * prefix test in the Python side of this project was wrong in the way its own comment made hardest to
     * see: it said {@code fe80::/10} and matched {@code fe80:}, which is /16, so {@code fe90::} through
     * {@code febf::} read as routable. The stdlib checks the real ten bits. A scope id is stripped first,
     * since {@code fe80::1%eth0} is a shape XO can return.
     *
     * <p>{@link #isAddressLiteral} before it is load-bearing, and a character class alone is not enough:
     * {@code getByName} resolves anything that is not a literal, and {@code cafe}, {@code abc.def} and
     * {@code dead.beef} are spelled entirely in hex digits and dots. Measured on this JVM, each costs a
     * real DNS round trip (53.3ms, 27.9ms, 26.3ms) on a provisioning thread, for a string the appliance
     * supplied. A colon-carrying value that is not a valid literal is refused by the resolver without a
     * lookup (0.3ms), so the shape test only has to catch the hostname-shaped ones.
     */
    private static boolean isRoutable(String address) {
        String literal = address.split("%", 2)[0].trim();
        if (literal.isEmpty()) {
            return false;
        }
        if (!isAddressLiteral(literal)) {
            LOGGER.fine(() -> "ignoring mainIpAddress " + address + ", which is not an address literal");
            return false;
        }
        InetAddress parsed;
        try {
            parsed = InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            LOGGER.fine(() -> "ignoring unparseable mainIpAddress " + address);
            return false;
        }
        if (parsed.isLinkLocalAddress()) {
            LOGGER.fine(() -> "ignoring link-local " + address + "; nothing can connect to it");
            return false;
        }
        return true;
    }

    /**
     * Whether this string can only be an address literal, checked before it is handed to a resolver.
     *
     * <p>Two conditions, and both are needed. The character class rules out what a hostname may contain
     * and an address may not. The shape rules out the hostnames spelled entirely within that class:
     * {@code cafe} passes a hex-only test and then resolves. Only a colon, which no hostname carries, or
     * four dot-separated decimal groups can follow.
     *
     * <p>The range check on those groups is not pedantry. {@code 999.999.999.999} is four groups of
     * digits, fails {@code InetAddress}'s own literal parse, and falls through to a 29.6ms DNS lookup.
     * Measured, and the reason this does not stop at counting groups.
     */
    // Package-private for the test, deliberately. Its whole job is to stop a value reaching the resolver,
    // and that is invisible from primaryIpAddress: with the range check removed, 999.999.999.999 still
    // ends as Optional.empty, just 29.6ms later and via the network. An outcome assertion cannot see a
    // guard against a side effect, so this one is asserted where it can fail.
    static boolean isAddressLiteral(String literal) {
        boolean colon = false;
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == ':') {
                colon = true;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.')) {
                return false;
            }
        }
        return colon || isDottedQuad(literal);
    }

    /** Four dot-separated decimal groups, each in 0-255. Nothing else is an IPv4 literal. */
    private static boolean isDottedQuad(String literal) {
        String[] groups = literal.split("\\.", -1);
        if (groups.length != 4) {
            return false;
        }
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 3) {
                return false;
            }
            for (int i = 0; i < group.length(); i++) {
                if (group.charAt(i) < '0' || group.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(group) > 255) {
                return false;
            }
        }
        return true;
    }

    @Override
    @NonNull
    public VmState state(@NonNull VmRef vm) {
        return switch (get(API + "/vms/" + vm.value() + "?fields=power_state")
                .path("power_state")
                .asText("")) {
            case "Halted" -> VmState.HALTED;
            case "Running" -> VmState.RUNNING;
            case "Paused" -> VmState.PAUSED;
            case "Suspended" -> VmState.SUSPENDED;
            default -> VmState.UNKNOWN;
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>No disks are captured first, because there is nothing to capture: this route deletes them
     * unconditionally and offers no way to opt out. That happens to be exactly what the plugin wants, but
     * it is a capability lost rather than a setting chosen, and the ordering trap this interface warns
     * about is now XO's to get right.
     *
     * <p>The already-gone rule is the same as the XAPI backend's and exists for the same race (#145): two
     * teardowns can reach one VM, and an operator deleting it by hand beats both. XO answers a request for
     * an object it cannot resolve with <b>404</b>, which reports this method's goal state rather than a
     * failure. The status is what is matched, not the message, so a 404 quoted inside some other failure
     * cannot swallow a real one.
     */
    @Override
    public void destroyWithDisks(@NonNull VmRef vm) {
        refuseForeignHandle(vm);
        String path = API + "/vms/" + vm.value();
        RestTransport.RestResponse resp;
        try {
            resp = transport.send("DELETE", path, null, ACTION_TIMEOUT);
        } catch (IOException e) {
            throw new HypervisorException("DELETE " + path + ": transport error: " + e.getMessage(), e);
        }
        // Checked here too, not only in call(): this verb talks to the transport directly, so it would
        // otherwise read a 202 as a completed teardown and drop the leaked-VM entry for a VM still running.
        refuseAsyncAccepted("DELETE", path, resp, null);
        if (resp.isSuccess()) {
            return;
        }
        if (resp.status() == 404 && isNoSuchObject(resp.body(), vm.value())) {
            LOGGER.info(() ->
                    "VM " + vm.value() + " was already gone when teardown reached it;" + " treating that as destroyed");
            return;
        }
        throw failure("DELETE", path, resp.status(), resp.body());
    }

    /**
     * Refuse a handle this backend never minted, the XO counterpart of {@link XapiClient}'s own check (#223).
     *
     * <p>The XAPI side refuses a foreign handle because XAPI answers one with {@code HANDLE_INVALID}, which
     * its already-gone rule reads as "destroyed". This direction is worse, and the measurement is the reason
     * the guard is here rather than left to the appliance: <b>XO resolves a XAPI {@code OpaqueRef} wherever
     * it takes a VM id</b>. Measured on the lab pool 2026-09-20, {@code GET /rest/v0/vms/<OpaqueRef>}
     * returned the VM and {@code DELETE} answered 204. So a ref misrouted into an XO client does not
     * harmlessly 404; against the same pool it can destroy the VM the caller did not name.
     *
     * <p>The punctuation check is not decoration. Object ids come from XO and are deliberately not
     * percent-encoded on the way into the path ({@link #encodeSegment}'s contract), so a value carrying a
     * slash, a query or a fragment addresses a different route entirely. That route answers 404, and a bare
     * status check would have read it as a clean teardown.
     */
    private static void refuseForeignHandle(@NonNull VmRef vm) {
        String ref = vm.value();
        if (ref.startsWith(VmRef.XAPI_REF_PREFIX)) {
            throw new HypervisorException("refusing to destroy " + ref + ": that is a XAPI handle, and this is the"
                    + " Xen Orchestra backend. A VM is only destroyable through the backend that created it;"
                    + " this appliance would resolve it and delete whatever it names.");
        }
        // Blank is deliberately not checked: VmRef's own constructor refuses null and blank, so a check
        // here would be unreachable and no test could make it fire.
        if (ref.indexOf('/') >= 0 || ref.indexOf('?') >= 0 || ref.indexOf('#') >= 0) {
            throw new HypervisorException("refusing to destroy '" + ref + "': not a VM id from this backend."
                    + " It would address a different route, whose 404 reads as an already-destroyed VM.");
        }
    }

    /**
     * Whether a 404 body is XO saying it has no such object, rather than something else that answered.
     *
     * <p>Used to qualify the 404 that teardown treats as its goal state. The bare status check this
     * replaced was weaker than that: an appliance too old to route the call, a reverse proxy that does not
     * map {@code /rest/v0}, or a renamed route all answer 404, and every one of them was being recorded as a
     * clean teardown. The caller then stops retrying, so the VM becomes invisible to the plugin rather than
     * merely un-destroyed.
     *
     * <p>Checking only for an {@code error} field separated those today and would stop separating them the
     * day XO adds a catch-all handler answering unknown routes in JSON. Today it has none: the REST API
     * mounts no not-found handler, so an unrouted call falls through to Express's HTML page, measured on the
     * lab appliance (xo-server 5.208.3) as {@code Cannot PUT ...}. So this checks the shape XO's own 404
     * actually has. Every 404 its REST error handler produces comes from {@code noSuchObject(id, type)}
     * ({@code @xen-orchestra/rest-api} {@code generic-error-handler.middleware.mts}), which carries
     * {@code data: {id, type}}; measured the same way, {@code {"error":"no such VM <id>","data":{"id":
     * "<id>","type":"VM"}}}. {@code type} is not required, because {@code noSuchObject} is also called with
     * an id alone.
     *
     * <p>With an {@code expectedId}, the object must also be the one asked about. That is the XAPI
     * backend's already-gone rule, which checks the error parameters name the very ref being destroyed, on
     * the stated grounds that the code alone is not enough: a generic handler cannot echo back an id it never parsed.
     * The message must also be the one XO's formatter builds from that id, {@code no such ${type || 'object'}
     * ${id}} ({@code xo-common/api-errors.js}), so a JSON 404 that happens to carry the id in {@code data}
     * is not taken for XO's. The cost is a dependency on that wording: were XO to change it, teardown would
     * stop recognising an already-deleted VM and report it as a failure, loudly, rather than pass something
     * it should not.
     *
     * <p>Without one, only the shape is asked about. That is the 404 hint's question: it has no object in
     * mind, only whether XO routed the call at all.
     */
    private static boolean isNoSuchObject(@CheckForNull String body, @CheckForNull String expectedId) {
        if (body == null || body.isBlank()) {
            return false;
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(body);
        } catch (IOException notJson) {
            return false;
        }
        if (parsed == null || !parsed.path("error").isTextual()) {
            return false;
        }
        // No separate blank check: VmRef refuses a blank value, so when an id is expected, equality
        // already rules a blank one out.
        JsonNode id = parsed.path("data").path("id");
        if (!id.isTextual()) {
            return false;
        }
        if (expectedId == null) {
            return true;
        }
        JsonNode type = parsed.path("data").path("type");
        String named = type.isTextual() && !type.asText().isEmpty() ? type.asText() : "object";
        return expectedId.equals(id.asText())
                && ("no such " + named + " " + expectedId)
                        .equals(parsed.path("error").asText());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deliberately not {@code GET /rest/v0/ping}: that route is declared {@code @Security('none')}, so
     * it answers pong to an unauthenticated caller and would report a working connection for a token that
     * is wrong, missing, or expired. Listing pools is the cheap authenticated round trip, the same
     * reasoning as the XAPI backend's {@code pool.get_all}.
     *
     * <p>Then one capability probe, because authenticating proves nothing about whether the appliance can
     * provision. {@code PATCH /vms/{id}} carries the sizing and the guest-data seed, and an xo-server too old
     * to route it (absent on 5.192.1, present on 5.208.3, #254) passes the pools read and fails only at the
     * first provision. See {@link #probePatchRoute()}.
     *
     * <p>The template-name field check also calls this and stays quiet when it throws, so on a too-old
     * appliance that field says nothing; Test Connection on the same form is what reports it.
     */
    @Override
    public void ping() {
        get(API + "/pools?fields=id");
        probePatchRoute();
    }

    /**
     * Ask whether the appliance routes {@code PATCH /vms/{id}}, without being able to change anything.
     *
     * <p>The target is the all-zero uuid, which no VM has, and the body is empty. Measured on the lab
     * appliance (xo-server 5.208.3) 2026-09-25: that request answers XO's own 404,
     * {@code {"error":"no such VM 0000...","data":{"id":"0000...","type":"VM"}}}, while a method the
     * appliance does not route on the same path answers Express's HTML page instead. The first means the
     * route exists and only the object is missing; the second is the too-old appliance.
     *
     * <p>Only the first answer passes, and it has to name the zero id. Everything else is a failure, 2xx
     * included: no real appliance can patch an object that does not exist, so a success means something
     * other than XO answered, and saying OK on that would be the green Test Connection this probe exists to
     * prevent. A 401 or 403 here goes through {@link #failure} and gets its usual hint, which matters because
     * a plan or role can allow the pools read and still refuse a write.
     */
    private void probePatchRoute() {
        String path = API + "/vms/" + PROBE_ID;
        RestTransport.RestResponse resp;
        try {
            resp = transport.send("PATCH", path, "{}", READ_TIMEOUT);
        } catch (IOException e) {
            throw new HypervisorException("PATCH " + path + ": transport error: " + e.getMessage(), e);
        }
        if (resp.status() == 404 && isNoSuchObject(resp.body(), PROBE_ID)) {
            return;
        }
        if (resp.isSuccess()) {
            throw new HypervisorException("PATCH " + path + ": HTTP " + resp.status() + " for a VM id that cannot"
                    + " exist. Xen Orchestra answers that with 404 naming the id, so something other than"
                    + " xo-server answered, and whether it can provision is unknown.");
        }
        throw failure("PATCH", path, resp.status(), resp.body());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nothing to release. Unlike XAPI, where a login allocates a session that has to be logged out, the
     * XO token is minted by an operator out of band and is what every request carries; there is no
     * per-client state on the appliance and closing must not revoke the operator's token.
     */
    @Override
    public void close() {
        // no session to release
    }
}
