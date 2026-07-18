package io.jenkins.plugins.xcpng.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * {@link HypervisorClient} over XAPI's JSON-RPC transport ({@code /jsonrpc}). Hand-rolled because
 * there is no usable Java XenAPI SDK; it mirrors {@code tools/xapi.py}, which proved how few methods
 * the plugin needs. It speaks JSON-RPC only: the XML-RPC endpoint returns a different error envelope.
 *
 * <p>Session handling (re-login on {@code SESSION_INVALID}) stays inside this class and never reaches
 * the interface. The {@code ~500-line} kill criterion applies here; if this balloons, that is a signal
 * to move the backend to Xen Orchestra where clone and cloud-init are first-class.
 *
 * <p>Not thread-safe: an instance holds one mutable session. Provisioning must use one client per
 * operation (the intended model) rather than sharing an instance across threads.
 */
public final class XapiClient implements HypervisorClient {

    private static final Logger LOGGER = Logger.getLogger(XapiClient.class.getName());
    private static final Pattern OPAQUE_REF = Pattern.compile("OpaqueRef:[0-9a-fA-F-]+");

    private final JsonRpcTransport transport;
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
    public XapiClient(
            @NonNull String poolUrl, @NonNull String user, @NonNull String password, boolean trustSelfSigned) {
        this(new HttpTransport(poolUrl, trustSelfSigned), user, password);
        if (trustSelfSigned) {
            LOGGER.warning("TLS verification disabled for " + poolUrl
                    + " (trustSelfSigned). Traffic is exposed to interception.");
        }
    }

    /** For tests: inject a transport that replays recorded JSON fixtures. */
    XapiClient(@NonNull JsonRpcTransport transport, @NonNull String user, @NonNull String password) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.user = Objects.requireNonNull(user, "user");
        this.password = Objects.requireNonNull(password, "password");
    }

    // -- plumbing ---------------------------------------------------------

    private JsonNode raw(String method, List<Object> params) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id.incrementAndGet());
        body.put("method", method);
        ArrayNode arr = body.putArray("params");
        for (Object p : params) {
            arr.add(MAPPER.<JsonNode>valueToTree(p));
        }
        String responseBody;
        try {
            responseBody = transport.post(MAPPER.writeValueAsString(body));
        } catch (IOException e) {
            throw new HypervisorException(method + ": transport error: " + e.getMessage(), e);
        }
        if (responseBody == null) {
            // Any transport must hand back a body to parse. Guard here so a null (from HttpTransport
            // or any other implementation) surfaces as a clear failure instead of an NPE in readTree.
            throw new HypervisorException(method + ": transport returned no response body");
        }
        JsonNode payload;
        try {
            payload = MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new HypervisorException(method + ": malformed response: " + e.getMessage(), e);
        }
        // A bare JSON scalar (5, null, true) is not an object; treat anything non-object as malformed
        // up front rather than tripping over it below, the same guard tools/xapi.py learned to add.
        if (payload == null || !payload.isObject()) {
            throw new HypervisorException(
                    method + ": expected a JSON object, got " + (payload == null ? "null" : payload.getNodeType()));
        }
        JsonNode error = payload.get("error");
        if (error != null && !error.isNull()) {
            String message = error.path("message").asText("UNKNOWN");
            if (message.contains("HOST_IS_SLAVE")) {
                // The pool returns the master's address in the error data. v0 does not auto-redirect
                // (the lab is a single host), so turn the opaque failure into an actionable one and
                // point the operator at the master. Auto-redirect is a TODO for a multi-host backend.
                throw new HypervisorException(method + ": this host is a pool member, not the master."
                        + " Point the cloud's poolUrl at the pool master at "
                        + error.path("data").path(0).asText("(address not reported)"));
            }
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
        for (Object p : params) {
            withSession.add(p);
        }
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
        session = raw("session.login_with_password", List.of(user, password, "", "xcpng-cloud"))
                .asText();
    }

    // -- async task helper ------------------------------------------------

    /**
     * Block until a task settles; return its raw result string. Void tasks (Async.VM.start,
     * clean_shutdown) settle with an empty result, so this must not require one; only the callers
     * that expect an object reference parse it (see {@link #awaitTaskRef}).
     */
    private String awaitTask(String task) {
        long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        try {
            while (true) {
                String status = call("task.get_status", task).asText();
                if ("success".equals(status)) {
                    return call("task.get_result", task).asText("");
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

    /** Await a task that yields an object reference (e.g. VM.clone), extracting the OpaqueRef. */
    private String awaitTaskRef(String task) {
        var m = OPAQUE_REF.matcher(awaitTask(task));
        if (!m.find()) {
            throw new HypervisorException("task " + task + " produced no object reference");
        }
        return m.group();
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HypervisorException("interrupted while polling a task");
        }
    }

    /**
     * Size a clone's vCPUs, writing the two values in whichever order keeps XAPI's invariant true.
     *
     * <p>XAPI enforces {@code 0 < VCPUs_at_startup <= VCPUs_max} on *every* write, and a clone inherits
     * the template's counts. So neither fixed order works for both directions: setting max first breaks
     * when shrinking (the inherited at_startup still exceeds the new max), and setting at_startup first
     * breaks when growing (it would exceed the inherited max). Read the clone's current max and move the
     * value that keeps the pair legal at each step.
     */
    private void setVcpus(String vm, int vcpus) {
        int currentMax = call("VM.get_VCPUs_max", vm).asInt();
        String target = String.valueOf(vcpus);
        if (vcpus <= currentMax) {
            // Shrinking: bring at_startup down under the new ceiling before lowering the ceiling onto it.
            call("VM.set_VCPUs_at_startup", vm, target);
            call("VM.set_VCPUs_max", vm, target);
        } else {
            // Growing: raise the ceiling first, so at_startup has room to follow.
            call("VM.set_VCPUs_max", vm, target);
            call("VM.set_VCPUs_at_startup", vm, target);
        }
    }

    // -- the verbs --------------------------------------------------------

    @Override
    @NonNull
    public VmRef resolveTemplate(@NonNull String name) {
        ensureSession();
        int byName = 0;
        List<String> matches = new ArrayList<>();
        for (JsonNode ref : call("VM.get_by_name_label", name)) {
            byName++;
            if (call("VM.get_is_a_template", ref.asText()).asBoolean()) {
                matches.add(ref.asText());
            }
        }
        if (matches.isEmpty()) {
            String detail = byName == 0
                    ? "no VM or template by that name exists"
                    : byName + " VM(s) carry that name but none is a template";
            throw new HypervisorException("no template named '" + name + "' on this pool (" + detail + ")");
        }
        if (matches.size() > 1) {
            // First-match would be non-deterministic and could clone the wrong image.
            throw new HypervisorException(matches.size() + " templates are named '" + name
                    + "'; rename them so the name is unique before provisioning against it");
        }
        return new VmRef(matches.get(0));
    }

    @Override
    @NonNull
    public VmRef cloneFromTemplate(@NonNull VmRef template, @NonNull ProvisionSpec spec) {
        if (spec.placementHint() != null) {
            // Reject before opening a session, so a bad spec fails with this local message rather than
            // as a connection or auth error, and leaves no VM behind. Placement (start-on-host,
            // affinity) is not wired in v0, which targets a single-host pool; reject rather than
            // silently ignore, so a caller cannot believe it placed a VM when it did not.
            throw new HypervisorException("placementHint is not honoured in v0 (single-host pool); leave it null");
        }
        ensureSession();
        String task = call("Async.VM.clone", template.value(), spec.name()).asText();
        String vm = awaitTaskRef(task);
        // The clone exists now, so anything past this point that throws (a rejected disk layout, a
        // set_* call that fails) would leave a VM and its disks behind. Destroy the clone on any such
        // failure before rethrowing, the same self-cleanup the bootstrap path owes its own VDIs.
        try {
            // VM.clone of a template yields a template; make it a runnable VM, then size it. VM.clone
            // copies the source's vCPU and memory, so each clone overrides them here.
            call("VM.set_is_a_template", vm, false);
            markOwner(vm, spec.owner());
            setVcpus(vm, spec.vcpus());
            String mem = String.valueOf(spec.memoryBytes());
            call("VM.set_memory_limits", vm, mem, mem, mem, mem);
            if (spec.diskBytes() != null) {
                // Grow the single root disk; a genericcloud image auto-expands its root FS on first boot.
                // Refuse ambiguity rather than resize an arbitrary disk.
                List<String> disks = diskVdis(vm);
                if (disks.size() != 1) {
                    throw new HypervisorException(
                            "cannot honour diskBytes: expected one disk on the clone, found " + disks.size());
                }
                call("VDI.resize", disks.get(0), String.valueOf(spec.diskBytes()));
            }
            seedGuestData(vm, spec.guestData());
        } catch (RuntimeException e) {
            // Clear the interrupt flag for the duration of the cleanup, and restore it afterwards. sleep()
            // re-interrupts the thread before throwing, and destroyWithDisks blocks on HTTP, which fails
            // immediately on an already-interrupted thread -- so an interrupt while polling the clone task
            // would otherwise leak the very clone and copy-on-write disks this cleanup exists to reclaim.
            boolean interrupted = Thread.interrupted();
            try {
                destroyWithDisks(new VmRef(vm));
            } catch (RuntimeException cleanup) {
                // Best effort. The original failure is what the caller needs, so keep it and just note
                // that the half-configured clone could not be reclaimed.
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
     * {@code other_config} key stamped on every clone this plugin provisions, holding the owning cloud's
     * name. The recovery contract with {@code tools/reaper.py}, which selects on this key rather than on a
     * name prefix: names drift (they already did, silently, and the reaper matched nothing for it), whereas
     * a VM that carries this key was provisioned by this plugin and by nothing else. A golden image, an
     * operator's VM, and a snapshot cannot acquire it by being named unluckily.
     *
     * <p>Change this string and the reaper stops finding VMs the plugin leaks. Both sides must move together.
     */
    public static final String OWNER_KEY = "xcpng-cloud";

    /**
     * Record the owning cloud on the VM record so an out-of-band sweep can find this clone later. Merged
     * onto whatever the clone inherited from the template, rather than replacing {@code other_config}
     * wholesale: XCP-ng itself keeps keys there (and a golden image may carry its own), and a clone that
     * dropped them would be a worse citizen than one the reaper cannot see.
     */
    private void markOwner(String vm, @CheckForNull String owner) {
        if (owner == null || owner.isBlank()) {
            return;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        JsonNode existing = call("VM.get_other_config", vm);
        if (existing.isObject()) {
            existing.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    merged.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        merged.put(OWNER_KEY, owner);
        call("VM.set_other_config", vm, merged);
    }

    /** Xenstore path the guest agent reads its seed from. Only {@code vm-data/*} keys reach the guest. */
    private static final String GUEST_DATA_PREFIX = "vm-data/jenkins/";

    /**
     * Write the per-clone seed (controller URL, node name, JNLP secret) into the VM's xenstore so the
     * guest can start its inbound agent unattended. Proven on the pool: only {@code vm-data/*} keys are
     * pushed into the guest, and the write must happen before the VM starts (setting xenstore-data on a
     * running VM does not propagate). Called from {@link #cloneFromTemplate} before the clone is started.
     *
     * <p>The keys are merged onto whatever the clone inherited rather than replacing the whole map, so a
     * template that carries its own xenstore-data keeps them.
     */
    private void seedGuestData(String vm, Map<String, String> guestData) {
        if (guestData.isEmpty()) {
            return;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        JsonNode existing = call("VM.get_xenstore_data", vm);
        if (existing.isObject()) {
            // xenstore-data is a string-to-string map, so a JSON null should never appear; guard anyway
            // so a stray null value is dropped rather than written back as the literal string "null".
            existing.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    merged.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        for (Map.Entry<String, String> e : guestData.entrySet()) {
            merged.put(GUEST_DATA_PREFIX + e.getKey(), e.getValue());
        }
        call("VM.set_xenstore_data", vm, merged);
    }

    @Override
    public void start(@NonNull VmRef vm) {
        ensureSession();
        awaitTask(call("Async.VM.start", vm.value(), false, false).asText());
    }

    @Override
    public void clearGuestSecret(@NonNull VmRef vm) {
        ensureSession();
        // Remove just the secret key from xenstore-data; url/name stay. VM.remove_from_xenstore_data is a
        // per-key delete and a no-op when the key is absent, so this needs no read-merge-write. Setting
        // xenstore-data on a running VM does not propagate to the guest, but that is fine: this only scrubs
        // the VM record, which the guest already read at boot.
        call("VM.remove_from_xenstore_data", vm.value(), GUEST_DATA_PREFIX + "secret");
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
        JsonNode gmNode = call("VM.get_guest_metrics", vm.value());
        if (gmNode.isNull()) {
            return Optional.empty();
        }
        String gm = gmNode.asText("");
        if (gm.isEmpty() || gm.contains("NULL")) {
            return Optional.empty();
        }
        JsonNode networks = call("VM_guest_metrics.get_networks", gm);
        // "0/ip" is the first NIC's IPv4. A multi-NIC or IPv6-only guest may not populate it and would
        // read as "no address"; acceptable for v0, whose inbound launcher never needs one.
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
        // Known, accepted hazard: XAPI's power_state can lie. A VM has been observed reading Halted
        // (domid -1) from XAPI while the domain was still running on dom0, so this guard skips the
        // hard_shutdown and VM.destroy then takes a live domain's disk. There is no fix from here:
        // this client speaks only JSON-RPC and the inbound/JNLP design holds no SSH credential, so
        // it has no second opinion, and asking XAPI to check XAPI inherits the same stale record.
        // The operator-side safety net is tools/reaper.py --dom0-check, which reads `xl list` off
        // dom0 before a sweep. See README "Known limitations".
        if (!"Halted".equals(call("VM.get_power_state", vm.value()).asText(""))) {
            call("VM.hard_shutdown", vm.value());
        }
        call("VM.destroy", vm.value());
        List<String> orphaned = new ArrayList<>();
        for (String vdi : vdis) {
            try {
                call("VDI.destroy", vdi);
            } catch (HypervisorException e) {
                // Try every disk even after one fails: giving up halfway orphans the rest.
                LOGGER.warning("VDI " + vdi + " survived teardown: " + e.getMessage());
                orphaned.add(vdi);
            }
        }
        if (!orphaned.isEmpty()) {
            // The VM is gone but storage leaked. Surface it so an operator (or the retention path)
            // knows the teardown was partial, rather than reporting a clean destroy.
            throw new HypervisorException("teardown leaked " + orphaned.size() + " VDI(s): " + orphaned);
        }
    }

    private List<String> diskVdis(String vm) {
        // A VDI must appear once even if several Disk VBDs map to it (a multi-attached disk, or a pool
        // that reports a duplicate VBD->VDI mapping). A set keyed on the ref keeps destroyWithDisks from
        // destroying the same VDI twice, where the second destroy fails and looks like a leak.
        Set<String> vdis = new LinkedHashSet<>();
        for (JsonNode vbd : call("VM.get_VBDs", vm)) {
            if (!"Disk".equals(call("VBD.get_type", vbd.asText()).asText(""))) {
                continue;
            }
            JsonNode vdiNode = call("VBD.get_VDI", vbd.asText());
            if (vdiNode.isNull()) {
                continue;
            }
            String vdi = vdiNode.asText("");
            if (!vdi.isEmpty() && !vdi.contains("NULL")) {
                vdis.add(vdi);
            }
        }
        return new ArrayList<>(vdis);
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
}
