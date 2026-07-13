package io.jenkins.plugins.xcpng.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * {@link HypervisorClient} over XAPI's JSON-RPC transport ({@code /jsonrpc}). Hand-rolled because
 * there is no usable Java XenAPI SDK; it mirrors {@code tools/xapi.py}, which proved how few methods
 * the plugin needs. It speaks JSON-RPC only: the XML-RPC endpoint returns a different error envelope.
 *
 * <p>Session handling (re-login on {@code SESSION_INVALID}) stays inside this class and never reaches
 * the interface. The {@code ~500-line} kill criterion applies here; if this balloons, that is a signal
 * to move the backend to Xen Orchestra where clone and cloud-init are first-class.
 */
public final class XapiClient implements HypervisorClient {

    private static final Logger LOGGER = Logger.getLogger(XapiClient.class.getName());
    private static final Pattern OPAQUE_REF = Pattern.compile("OpaqueRef:[0-9a-fA-F-]+");

    private final JsonRpcTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger id = new AtomicInteger();
    private final String user;
    private final String password;
    private String session;

    /**
     * @param poolUrl base URL of the pool, e.g. {@code https://192.168.1.87}
     * @param user XAPI user (a credential the plugin resolves at point of use, never stored here)
     * @param password XAPI password
     * @param trustSelfSigned accept a self-signed pool certificate. Off by default; a lab pool needs
     *     it, nothing else should. Turning it on disables both chain and hostname verification.
     */
    public XapiClient(@NonNull String poolUrl, @NonNull String user, @NonNull String password, boolean trustSelfSigned) {
        this(new HttpTransport(poolUrl, trustSelfSigned), user, password);
        if (trustSelfSigned) {
            LOGGER.warning("TLS verification disabled for " + poolUrl
                    + " (trustSelfSigned). Traffic is exposed to interception.");
        }
    }

    /** For tests: inject a transport that replays recorded JSON fixtures. */
    XapiClient(@NonNull JsonRpcTransport transport, @NonNull String user, @NonNull String password) {
        this.transport = transport;
        this.user = user;
        this.password = password;
    }

    // -- plumbing ---------------------------------------------------------

    private JsonNode raw(String method, List<Object> params) {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id.incrementAndGet());
        body.put("method", method);
        ArrayNode arr = body.putArray("params");
        for (Object p : params) {
            arr.add(mapper.<JsonNode>valueToTree(p));
        }
        String responseBody;
        try {
            responseBody = transport.post(mapper.writeValueAsString(body));
        } catch (IOException e) {
            throw new HypervisorException(method + ": transport error: " + e.getMessage(), e);
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(responseBody);
        } catch (IOException e) {
            throw new HypervisorException(method + ": malformed response: " + e.getMessage(), e);
        }
        // A bare JSON scalar (5, null, true) is not an object; treat anything non-object as malformed
        // up front rather than tripping over it below, the same guard tools/xapi.py learned to add.
        if (payload == null || !payload.isObject()) {
            throw new HypervisorException(method + ": expected a JSON object, got "
                    + (payload == null ? "null" : payload.getNodeType()));
        }
        JsonNode error = payload.get("error");
        if (error != null && !error.isNull()) {
            String message = error.path("message").asText("UNKNOWN");
            throw new HypervisorException(method + ": " + message + " " + error.path("data"));
        }
        JsonNode result = payload.get("result");
        if (result == null) {
            throw new HypervisorException(method + ": no result in " + payload);
        }
        return result;
    }

    /** Authenticated call, re-logging in once on a stale session. */
    private JsonNode call(String method, Object... params) {
        List<Object> withSession = new ArrayList<>();
        withSession.add(session);
        withSession.addAll(List.of(params));
        try {
            return raw(method, withSession);
        } catch (HypervisorException e) {
            if (!String.valueOf(e.getMessage()).contains("SESSION_INVALID")) {
                throw e;
            }
            login();
            withSession.set(0, session);
            return raw(method, withSession);
        }
    }

    private void login() {
        session = raw("session.login_with_password", List.of(user, password, "", "xcpng-cloud")).asText();
    }

    // -- async task helper ------------------------------------------------

    private String awaitTask(String task) {
        long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        try {
            while (true) {
                String status = call("task.get_status", task).asText();
                if ("success".equals(status)) {
                    var m = OPAQUE_REF.matcher(call("task.get_result", task).asText(""));
                    if (!m.find()) {
                        throw new HypervisorException("task result unparseable: " + task);
                    }
                    return m.group();
                }
                if ("failure".equals(status) || "cancelled".equals(status)) {
                    throw new HypervisorException("task " + status + ": " + call("task.get_error_info", task));
                }
                if (System.nanoTime() > deadline) {
                    throw new HypervisorException("task timeout: " + task);
                }
                sleep();
            }
        } finally {
            try {
                call("task.destroy", task);
            } catch (HypervisorException ignored) {
                // best effort; a leaked task record is harmless
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HypervisorException("interrupted while polling a task");
        }
    }

    // -- the verbs --------------------------------------------------------

    @Override
    @NonNull
    public VmRef resolveTemplate(@NonNull String name) {
        ensureSession();
        for (JsonNode ref : call("VM.get_by_name_label", name)) {
            if (call("VM.get_is_a_template", ref.asText()).asBoolean()) {
                return new VmRef(ref.asText());
            }
        }
        throw new HypervisorException("no template named '" + name
                + "' on this pool (a VM by that name is not a template)");
    }

    @Override
    @NonNull
    public VmRef cloneFromTemplate(@NonNull VmRef template, @NonNull ProvisionSpec spec) {
        ensureSession();
        String task = call("Async.VM.clone", template.value(), spec.name()).asText();
        String vm = awaitTask(task);
        // VM.clone of a template yields a template; make it a runnable VM, then size it. VM.clone
        // copies the source's vCPU and memory, so each clone overrides them here.
        call("VM.set_is_a_template", vm, false);
        call("VM.set_VCPUs_max", vm, String.valueOf(spec.vcpus()));
        call("VM.set_VCPUs_at_startup", vm, String.valueOf(spec.vcpus()));
        String mem = String.valueOf(spec.memoryBytes());
        call("VM.set_memory_limits", vm, mem, mem, mem, mem);
        return new VmRef(vm);
    }

    @Override
    public void start(@NonNull VmRef vm) {
        ensureSession();
        awaitTask(call("Async.VM.start", vm.value(), false, false).asText());
    }

    @Override
    @NonNull
    public Optional<String> primaryIpAddress(@NonNull VmRef vm) {
        ensureSession();
        // A halted VM keeps a stale guest_metrics husk, so gate on power state, then read the
        // networks map's contents rather than trusting the ref to be non-null.
        if (state(vm) != VmState.RUNNING) {
            return Optional.empty();
        }
        String gm = call("VM.get_guest_metrics", vm.value()).asText("");
        if (gm.isEmpty() || gm.contains("NULL")) {
            return Optional.empty();
        }
        JsonNode networks = call("VM_guest_metrics.get_networks", gm);
        JsonNode ip = networks.get("0/ip");
        return ip == null || ip.asText().isBlank() ? Optional.empty() : Optional.of(ip.asText());
    }

    @Override
    @NonNull
    public VmState state(@NonNull VmRef vm) {
        ensureSession();
        return switch (call("VM.get_power_state", vm.value()).asText("")) {
            case "Halted" -> VmState.HALTED;
            case "Running" -> VmState.RUNNING;
            case "Paused" -> VmState.PAUSED;
            case "Suspended" -> VmState.SUSPENDED;
            default -> VmState.UNKNOWN;
        };
    }

    @Override
    public void stop(@NonNull VmRef vm) {
        ensureSession();
        awaitTask(call("Async.VM.clean_shutdown", vm.value()).asText());
    }

    @Override
    public void destroyWithDisks(@NonNull VmRef vm) {
        ensureSession();
        List<String> vdis = diskVdis(vm.value()); // capture before destroy, or the VDIs orphan
        if (!"Halted".equals(call("VM.get_power_state", vm.value()).asText(""))) {
            call("VM.hard_shutdown", vm.value());
        }
        call("VM.destroy", vm.value());
        for (String vdi : vdis) {
            try {
                call("VDI.destroy", vdi);
            } catch (HypervisorException e) {
                // Report and keep going: giving up halfway is how disks get orphaned.
                LOGGER.warning("VDI " + vdi + " survived teardown: " + e.getMessage());
            }
        }
    }

    private List<String> diskVdis(String vm) {
        List<String> vdis = new ArrayList<>();
        for (JsonNode vbd : call("VM.get_VBDs", vm)) {
            if (!"Disk".equals(call("VBD.get_type", vbd.asText()).asText(""))) {
                continue;
            }
            String vdi = call("VBD.get_VDI", vbd.asText()).asText("");
            if (!vdi.isEmpty() && !vdi.contains("NULL")) {
                vdis.add(vdi);
            }
        }
        return vdis;
    }

    @Override
    public void ping() {
        ensureSession();
        call("pool.get_all"); // a cheap authenticated round-trip proves connectivity and auth
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                raw("session.logout", List.of(session));
            } catch (HypervisorException ignored) {
                // logging out is best effort
            } finally {
                session = null;
            }
        }
    }

    private void ensureSession() {
        if (session == null) {
            login();
        }
    }

    // -- HTTP + TLS, the only part that touches the network ---------------

    private static final class HttpTransport implements JsonRpcTransport {

        private final HttpClient http;
        private final URI endpoint;

        HttpTransport(String poolUrl, boolean trustSelfSigned) {
            this.endpoint = URI.create(poolUrl.replaceAll("/+$", "") + "/jsonrpc");
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30));
            if (trustSelfSigned) {
                b.sslContext(trustAllContext());
                SSLParameters p = new SSLParameters();
                p.setEndpointIdentificationAlgorithm(null); // also skip hostname verification
                b.sslParameters(p);
            }
            this.http = b.build();
        }

        @Override
        public String post(String requestBody) throws IOException {
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            try {
                return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }

        private static SSLContext trustAllContext() {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] {new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, new SecureRandom());
                return ctx;
            } catch (java.security.GeneralSecurityException e) {
                throw new HypervisorException("cannot build a trust-all SSL context: " + e.getMessage(), e);
            }
        }
    }
}
