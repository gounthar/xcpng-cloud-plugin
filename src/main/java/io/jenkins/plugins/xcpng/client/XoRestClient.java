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
 *       the PATCH body type, so {@link XapiClient#OWNER_KEY} has no home here. The equivalent is
 *       {@code PUT /vms/{id}/tags/{tag}}, which bottoms out on XAPI {@code add_tags}. Tags are visible in
 *       the XO UI where {@code other_config} effectively is not, so an operator will see
 *       {@code xcpng-cloud:<cloud>} on every clone. <b>While both backends exist, a clone leaked by one
 *       is invisible to a sweep written for the other</b> -- {@code tools/reaper.py} selects on
 *       {@code other_config} and cannot see an XO-made VM at all.
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
     * XO-side counterpart of {@link XapiClient#OWNER_KEY}, and deliberately built from that constant: the
     * two markers have to be recognisable as the same thing by whatever sweeps for either.
     */
    public static final String OWNER_TAG_PREFIX = XapiClient.OWNER_KEY + ":";

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
        RestTransport.RestResponse resp;
        try {
            resp = transport.send(method, path, body == null ? null : body.toString(), timeout);
        } catch (IOException e) {
            throw new HypervisorException(method + " " + path + ": transport error: " + e.getMessage(), e);
        }
        JsonNode payload = parse(method + " " + path, resp.body());
        if (!resp.isSuccess()) {
            throw failure(method, path, resp.status(), payload);
        }
        return payload;
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

    @NonNull
    private static HypervisorException failure(String method, String path, int status, JsonNode payload) {
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
        return new HypervisorException(method + " " + path + ": HTTP " + status + ": " + detail, code, params);
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
            throw new HypervisorException(matches.size() + " templates are named '" + name
                    + "'; rename them so the name is unique before provisioning against it");
        }
        return matches.get(0).toRef();
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
        JsonNode created = call(
                "POST", API + "/pools/" + handle.poolId() + "/actions/create_vm?sync=true", create, ACTION_TIMEOUT);
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
     * Size the clone and write its seed, in one PATCH, then stamp the owner tag.
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
        markOwner(vm, spec.owner());
    }

    /**
     * Record the owning cloud on the VM so an out-of-band sweep can find this clone later. A per-tag PUT
     * rather than the PATCH body's {@code tags} array, which is a whole-list replace and would drop
     * whatever the template carried.
     */
    private void markOwner(String vm, @CheckForNull String owner) {
        if (owner == null || owner.isBlank()) {
            return;
        }
        call("PUT", API + "/vms/" + vm + "/tags/" + encodeSegment(OWNER_TAG_PREFIX + owner), null, READ_TIMEOUT);
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
     * <p>The character check before it is load-bearing: {@code getByName} resolves anything that is not a
     * literal, so without it a garbage value would become a DNS lookup on a provisioning thread.
     */
    private static boolean isRoutable(String address) {
        String literal = address.split("%", 2)[0].trim();
        if (literal.isEmpty()) {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            boolean literalChar =
                    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':';
            if (!literalChar) {
                LOGGER.fine(() -> "ignoring mainIpAddress " + address + ", which is not an address literal");
                return false;
            }
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
        String path = API + "/vms/" + vm.value();
        RestTransport.RestResponse resp;
        try {
            resp = transport.send("DELETE", path, null, ACTION_TIMEOUT);
        } catch (IOException e) {
            throw new HypervisorException("DELETE " + path + ": transport error: " + e.getMessage(), e);
        }
        if (resp.isSuccess()) {
            return;
        }
        if (resp.status() == 404) {
            LOGGER.info(() ->
                    "VM " + vm.value() + " was already gone when teardown reached it;" + " treating that as destroyed");
            return;
        }
        throw failure("DELETE", path, resp.status(), parse("DELETE " + path, resp.body()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deliberately not {@code GET /rest/v0/ping}: that route is declared {@code @Security('none')}, so
     * it answers pong to an unauthenticated caller and would report a working connection for a token that
     * is wrong, missing, or expired. Listing pools is the cheap authenticated round trip, the same
     * reasoning as the XAPI backend's {@code pool.get_all}.
     */
    @Override
    public void ping() {
        get(API + "/pools?fields=id");
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
