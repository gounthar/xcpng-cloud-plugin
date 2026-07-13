package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals(new VmRef("OpaqueRef:tmpl"), c.resolveTemplate("jenkins-golden-debian"));

        t.isTemplate = false; // same name, but the object is an ordinary VM
        assertThrows(HypervisorException.class, () -> c.resolveTemplate("jenkins-golden-debian"));
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
        assertEquals("4096", mem.get(5).asText());
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
        String powerState = "Halted";

        @Override
        public String post(String body) {
            JsonNode req = read(body);
            requests.add(req);
            String method = req.get("method").asText();
            JsonNode id = req.get("id");
            Object result = switch (method) {
                case "session.login_with_password" -> "OpaqueRef:session";
                case "VM.get_by_name_label" -> List.of("OpaqueRef:tmpl");
                case "VM.get_is_a_template" -> isTemplate;
                case "Async.VM.clone" -> "OpaqueRef:task-clone";
                case "Async.VM.start" -> "OpaqueRef:task-start";
                case "Async.VM.clean_shutdown" -> "OpaqueRef:task-stop";
                case "task.get_status" -> "success";
                // Real XAPI task results wrap a hex OpaqueRef; the client regexes it out.
                case "task.get_result" -> "<value>OpaqueRef:1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d</value>";
                case "VM.get_power_state" -> powerState;
                case "VM.get_guest_metrics" -> "OpaqueRef:gm";
                case "VM_guest_metrics.get_networks" -> Map.of("0/ip", "192.168.1.50");
                case "VM.get_VBDs" -> List.of("OpaqueRef:vbd-disk", "OpaqueRef:vbd-cd");
                case "VBD.get_type" -> req.get("params").get(1).asText().contains("cd") ? "CD" : "Disk";
                case "VBD.get_VDI" -> "OpaqueRef:vdi-disk";
                case "pool.get_all" -> List.of();
                default -> ""; // set_*, destroy, task.destroy, hard_shutdown all return void-ish
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
