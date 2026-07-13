package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link XapiClient}'s verbs and error handling against a scripted transport, no pool. The
 * transport dispatches on the JSON-RPC method and records every request, so tests can assert both the
 * returned values and the order of calls, most importantly the destroy-with-disks teardown ordering.
 */
class XapiClientTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void resolveTemplateReturnsTheTemplateAndRejectsAPlainVm() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");

        assertEquals(new VmRef("OpaqueRef:tmpl-0"), c.resolveTemplate("jenkins-golden-debian"));

        t.isTemplate = false; // same name, but the object is an ordinary VM
        assertThrows(HypervisorException.class, () -> c.resolveTemplate("jenkins-golden-debian"));
    }

    @Test
    void resolveTemplateFailsWhenTheNameIsAmbiguous() {
        ScriptedTransport t = new ScriptedTransport();
        t.nameMatches = 2; // two templates share the name
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e =
                assertThrows(HypervisorException.class, () -> c.resolveTemplate("jenkins-golden-debian"));
        assertTrue(e.getMessage().contains("2 templates"), e.getMessage());
    }

    @Test
    void cloneResizesTheDiskWhenDiskBytesIsSet() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");
        c.cloneFromTemplate(new VmRef("OpaqueRef:tmpl"),
                new ProvisionSpec("agent", 2, 2048L, 8_000_000_000L, null, null));
        assertEquals("8000000000", paramsOf(t, "VDI.resize").get(2).asText());
        assertEquals("OpaqueRef:vdi-disk", paramsOf(t, "VDI.resize").get(1).asText());
    }

    @Test
    void cloneRejectsPlacementHintBeforeCreatingAnything() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");
        assertThrows(HypervisorException.class, () -> c.cloneFromTemplate(new VmRef("OpaqueRef:tmpl"),
                new ProvisionSpec("agent", 2, 2048L, null, "host-3", null)));
        assertFalse(t.methods().contains("Async.VM.clone"), "must not clone when the spec is rejected");
    }

    @Test
    void cloneDestroysThePartialCloneWhenTheDiskLayoutIsRejected() {
        ScriptedTransport t = new ScriptedTransport();
        t.extraDisk = true; // two disks, so a diskBytes resize can't pick one and the spec is rejected
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e = assertThrows(HypervisorException.class,
                () -> c.cloneFromTemplate(new VmRef("OpaqueRef:tmpl"),
                        new ProvisionSpec("agent", 2, 2048L, 8_000_000_000L, null, null)));
        assertTrue(e.getMessage().contains("expected one disk"), e.getMessage());
        // The clone already existed when the spec was rejected, so it must be torn down, not leaked.
        assertTrue(t.methods().contains("VM.destroy"), "the rejected clone must be destroyed");
    }

    @Test
    void aFailedTaskThrows() {
        ScriptedTransport t = new ScriptedTransport();
        t.taskStatus = "failure";
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e = assertThrows(HypervisorException.class, () -> c.start(new VmRef("OpaqueRef:vm-1")));
        assertTrue(e.getMessage().contains("failure"), e.getMessage());
    }

    @Test
    void destroyThatLeaksAVdiSurfacesIt() {
        ScriptedTransport t = new ScriptedTransport();
        t.vdiDestroyFails = true;
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e =
                assertThrows(HypervisorException.class, () -> c.destroyWithDisks(new VmRef("OpaqueRef:vm-1")));
        assertTrue(e.getMessage().contains("leaked"), e.getMessage());
        // The VM was still destroyed even though a disk survived.
        assertTrue(t.methods().contains("VM.destroy"));
    }

    @Test
    void hostIsSlaveBecomesAnActionableError() {
        ScriptedTransport t = new ScriptedTransport();
        t.hostIsSlave = true;
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e =
                assertThrows(HypervisorException.class, () -> c.state(new VmRef("OpaqueRef:vm-1")));
        assertTrue(e.getMessage().contains("192.168.1.99"), e.getMessage());
        assertTrue(e.getMessage().contains("master"), e.getMessage());
    }

    @Test
    void cloneUntemplatesAndSizesThenReturnsTheNewVm() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");

        VmRef vm = c.cloneFromTemplate(new VmRef("OpaqueRef:tmpl"),
                new ProvisionSpec("agent-7", 4, 4096L, null, null, null));

        assertEquals(new VmRef("OpaqueRef:1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d"), vm);
        // Clone, wait for the task, untemplate, then size, in that order.
        assertOrder(t, "Async.VM.clone", "task.get_status", "task.get_result",
                "VM.set_is_a_template", "VM.set_VCPUs_max", "VM.set_VCPUs_at_startup", "VM.set_memory_limits");
        assertEquals(false, paramsOf(t, "VM.set_is_a_template").get(2).asBoolean());
        assertEquals("4", paramsOf(t, "VM.set_VCPUs_max").get(2).asText());
        // static_min = dynamic_min = dynamic_max = static_max = memoryBytes
        JsonNode mem = paramsOf(t, "VM.set_memory_limits");
        assertEquals("4096", mem.get(2).asText());
        assertEquals("4096", mem.get(3).asText());
        assertEquals("4096", mem.get(4).asText());
        assertEquals("4096", mem.get(5).asText());
    }

    @Test
    void startSucceedsWhenTheAsyncTaskResultIsVoid() {
        // Async.VM.start settles with an empty result; the client must not demand an OpaqueRef.
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");
        c.start(new VmRef("OpaqueRef:vm-1")); // must not throw
        assertTrue(t.methods().contains("Async.VM.start"));
    }

    @Test
    void destroyWithDisksCapturesVdisBeforeTheVmThenDestroysThem() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");

        c.destroyWithDisks(new VmRef("OpaqueRef:vm-1"));

        // The load-bearing ordering: enumerate VBDs (to capture VDIs) before VM.destroy, and only
        // destroy the disk-backed VDI afterwards. The CD's VDI is never destroyed.
        assertOrder(t, "VM.get_VBDs", "VM.destroy", "VDI.destroy");
        List<String> methods = t.methods();
        assertTrue(methods.indexOf("VM.get_VBDs") < methods.indexOf("VM.destroy"), "capture before destroy");
        assertTrue(methods.indexOf("VM.destroy") < methods.indexOf("VDI.destroy"), "destroy disks after the VM");
        assertEquals(1, methods.stream().filter("VDI.destroy"::equals).count(), "only the Disk VBD's VDI");
        assertEquals("OpaqueRef:vdi-disk", paramsOf(t, "VDI.destroy").get(1).asText());
    }

    @Test
    void primaryIpIsEmptyWhenHalted() {
        ScriptedTransport t = new ScriptedTransport();
        t.powerState = "Halted";
        XapiClient c = new XapiClient(t, "root", "pw");
        assertEquals(Optional.empty(), c.primaryIpAddress(new VmRef("OpaqueRef:vm-1")));
    }

    @Test
    void primaryIpReadsTheNetworksMapWhenRunning() {
        ScriptedTransport t = new ScriptedTransport();
        t.powerState = "Running";
        XapiClient c = new XapiClient(t, "root", "pw");
        assertEquals(Optional.of("192.168.1.50"), c.primaryIpAddress(new VmRef("OpaqueRef:vm-1")));
    }

    @Test
    void stateMapsPowerState() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");
        for (Map.Entry<String, VmState> e : Map.of(
                "Halted", VmState.HALTED, "Running", VmState.RUNNING,
                "Paused", VmState.PAUSED, "Suspended", VmState.SUSPENDED, "wat", VmState.UNKNOWN).entrySet()) {
            t.powerState = e.getKey();
            assertEquals(e.getValue(), c.state(new VmRef("OpaqueRef:vm-1")));
        }
    }

    @Test
    void anErrorEnvelopeBecomesAHypervisorException() {
        XapiClient c = new XapiClient(body -> envelope(reqId(body), null,
                Map.of("message", "HANDLE_INVALID", "data", List.of("VM", "x"))), "root", "pw");
        HypervisorException e =
                assertThrows(HypervisorException.class, () -> c.state(new VmRef("OpaqueRef:vm-1")));
        assertTrue(e.getMessage().contains("HANDLE_INVALID"));
    }

    @Test
    void aBareScalarResponseIsMalformed() {
        XapiClient c = new XapiClient(body -> "5", "root", "pw");
        assertThrows(HypervisorException.class, c::ping);
    }

    @Test
    void aNullResponseBodyIsSurfacedNotAnNpe() {
        XapiClient c = new XapiClient(body -> null, "root", "pw");
        HypervisorException e = assertThrows(HypervisorException.class, c::ping);
        assertTrue(e.getMessage().contains("no response body"), e.getMessage());
    }

    @Test
    void aStaleSessionTriggersOneReloginThenSucceeds() {
        // First authenticated pool.get_all fails SESSION_INVALID; the client must log in again and retry.
        int[] poolCalls = {0};
        int[] logins = {0};
        JsonRpcTransport t = body -> {
            JsonNode req = read(body);
            String method = req.get("method").asText();
            if (method.equals("session.login_with_password")) {
                logins[0]++;
                return envelope(req.get("id"), "OpaqueRef:session", null);
            }
            if (method.equals("pool.get_all") && poolCalls[0]++ == 0) {
                return envelope(req.get("id"), null, Map.of("message", "SESSION_INVALID"));
            }
            return envelope(req.get("id"), List.of(), null);
        };
        new XapiClient(t, "root", "pw").ping();
        assertEquals(2, logins[0], "logged in once up front, once after SESSION_INVALID");
        assertEquals(2, poolCalls[0], "pool.get_all retried after re-login");
    }

    // -- scripted transport ----------------------------------------------

    /** Dispatches on method, records requests, returns canned results a happy path would produce. */
    private static final class ScriptedTransport implements JsonRpcTransport {
        final List<JsonNode> requests = new ArrayList<>();
        boolean isTemplate = true;
        int nameMatches = 1;
        String powerState = "Halted";
        String taskStatus = "success";
        boolean vdiDestroyFails = false;
        boolean hostIsSlave = false;
        boolean extraDisk = false;

        @Override
        public String post(String body) {
            JsonNode req = read(body);
            requests.add(req);
            String method = req.get("method").asText();
            JsonNode id = req.get("id");
            if (hostIsSlave && method.equals("VM.get_power_state")) {
                return envelope(id, null, Map.of("message", "HOST_IS_SLAVE", "data", List.of("192.168.1.99")));
            }
            if (vdiDestroyFails && method.equals("VDI.destroy")) {
                return envelope(id, null, Map.of("message", "VDI_IN_USE"));
            }
            Object result = switch (method) {
                case "session.login_with_password" -> "OpaqueRef:session";
                case "VM.get_by_name_label" -> {
                    List<String> refs = new ArrayList<>();
                    for (int i = 0; i < nameMatches; i++) {
                        refs.add("OpaqueRef:tmpl-" + i);
                    }
                    yield refs;
                }
                case "VM.get_is_a_template" -> isTemplate;
                case "Async.VM.clone" -> "OpaqueRef:task-clone";
                case "Async.VM.start" -> "OpaqueRef:task-start";
                case "Async.VM.clean_shutdown" -> "OpaqueRef:task-stop";
                case "task.get_status" -> taskStatus;
                // Clone-like tasks wrap a hex OpaqueRef; void tasks (start, clean_shutdown) settle
                // with an empty result. Returning a ref for everything would hide the void-task bug.
                case "task.get_result" -> req.get("params").get(1).asText().contains("clone")
                        ? "<value>OpaqueRef:1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d</value>"
                        : "";
                case "task.get_error_info" -> List.of("INTERNAL_ERROR", "boom");
                case "VM.get_power_state" -> powerState;
                case "VM.get_guest_metrics" -> "OpaqueRef:gm";
                case "VM_guest_metrics.get_networks" -> Map.of("0/ip", "192.168.1.50");
                case "VM.get_VBDs" -> extraDisk
                        ? List.of("OpaqueRef:vbd-disk", "OpaqueRef:vbd-disk2", "OpaqueRef:vbd-cd")
                        : List.of("OpaqueRef:vbd-disk", "OpaqueRef:vbd-cd");
                case "VBD.get_type" -> req.get("params").get(1).asText().contains("cd") ? "CD" : "Disk";
                case "VBD.get_VDI" -> "OpaqueRef:vdi-disk";
                case "pool.get_all" -> List.of();
                default -> ""; // set_*, resize, destroy, task.destroy, hard_shutdown all return void-ish
            };
            return envelope(id, result, null);
        }

        List<String> methods() {
            List<String> ms = new ArrayList<>();
            for (JsonNode r : requests) {
                ms.add(r.get("method").asText());
            }
            return ms;
        }
    }

    private static void assertOrder(ScriptedTransport t, String... methodsInOrder) {
        List<String> actual = t.methods();
        int last = -1;
        for (String m : methodsInOrder) {
            int at = actual.indexOf(m);
            assertTrue(at > last, m + " expected after the previous call, methods were " + actual);
            last = at;
        }
    }

    private static JsonNode paramsOf(ScriptedTransport t, String method) {
        for (JsonNode r : t.requests) {
            if (r.get("method").asText().equals(method)) {
                return r.get("params");
            }
        }
        throw new AssertionError(method + " was never called");
    }

    private static JsonNode reqId(String body) {
        return read(body).get("id");
    }

    private static JsonNode read(String body) {
        try {
            return M.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Build a JSON-RPC response with either a result or an error object. */
    private static String envelope(JsonNode id, Object result, Object error) {
        ObjectNode resp = M.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id == null ? M.nullNode() : id);
        if (error != null) {
            resp.set("error", M.valueToTree(error));
        } else {
            resp.set("result", M.valueToTree(result));
        }
        try {
            return M.writeValueAsString(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
