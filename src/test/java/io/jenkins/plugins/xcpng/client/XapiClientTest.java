package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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
        c.cloneFromTemplate(
                new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 2, 2048L, 8_000_000_000L, null, null));
        assertEquals("8000000000", paramsOf(t, "VDI.resize").get(2).asText());
        assertEquals("OpaqueRef:vdi-disk", paramsOf(t, "VDI.resize").get(1).asText());
    }

    @Test
    void cloneRejectsPlacementHintBeforeCreatingAnything() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");
        assertThrows(
                HypervisorException.class,
                () -> c.cloneFromTemplate(
                        new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 2, 2048L, null, "host-3", null)));
        assertFalse(t.methods().contains("Async.VM.clone"), "must not clone when the spec is rejected");
        assertFalse(
                t.methods().contains("session.login_with_password"), "must reject the spec before opening a session");
    }

    @Test
    void cloneDestroysThePartialCloneWhenTheDiskLayoutIsRejected() {
        ScriptedTransport t = new ScriptedTransport();
        t.extraDisk = true; // two disks, so a diskBytes resize can't pick one and the spec is rejected
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e = assertThrows(
                HypervisorException.class,
                () -> c.cloneFromTemplate(
                        new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 2, 2048L, 8_000_000_000L, null, null)));
        assertTrue(e.getMessage().contains("expected one disk"), e.getMessage());
        // The clone already existed when the spec was rejected, so it must be torn down, not leaked.
        assertTrue(t.methods().contains("VM.destroy"), "the rejected clone must be destroyed");
    }

    @Test
    void cloneInterruptedAfterTheCloneExistsStillDestroysItAndKeepsTheInterrupt() {
        ScriptedTransport t = new ScriptedTransport();
        // The clone exists, then the thread is interrupted while it is still being configured, so every
        // later call fails on the interrupt exactly as it would against a pool. The cleanup must still
        // reclaim the clone: this is the case that leaks a VM and its copy-on-write disks.
        t.interruptOn = "VM.set_is_a_template";
        XapiClient c = new XapiClient(t, "root", "pw");
        try {
            assertThrows(
                    HypervisorException.class,
                    () -> c.cloneFromTemplate(
                            new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 2, 2048L, null, null, null)));

            assertTrue(
                    t.methods().contains("VM.destroy"),
                    "an interrupted clone must still be destroyed, not leaked: " + t.methods());
            assertTrue(
                    Thread.currentThread().isInterrupted(), "the interrupt must be restored once the cleanup has run");
        } finally {
            // Never leave the flag set on a shared JUnit thread, or the next test inherits it.
            Thread.interrupted();
        }
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
        HypervisorException e = assertThrows(HypervisorException.class, () -> c.state(new VmRef("OpaqueRef:vm-1")));
        assertTrue(e.getMessage().contains("192.168.1.99"), e.getMessage());
        assertTrue(e.getMessage().contains("master"), e.getMessage());
    }

    @Test
    void cloneUntemplatesAndSizesThenReturnsTheNewVm() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");

        VmRef vm = c.cloneFromTemplate(
                new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent-7", 4, 4096L, null, null, null));

        assertEquals(new VmRef("OpaqueRef:1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d"), vm);
        // Clone, wait for the task, untemplate, then size, in that order.
        assertOrder(
                t,
                "Async.VM.clone",
                "task.get_status",
                "task.get_result",
                "VM.set_is_a_template",
                "VM.set_VCPUs_max",
                "VM.set_VCPUs_at_startup",
                "VM.set_memory_limits");
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
    void cloneCanShrinkVcpusBelowTheTemplatesCount() {
        ScriptedTransport t = new ScriptedTransport(); // template has 2 vCPUs, as the golden image does
        XapiClient c = new XapiClient(t, "root", "pw");

        // Setting max first would try 0 < 2 <= 1 while at_startup still holds the inherited 2, which the
        // pool rejects with INVALID_VALUE. at_startup has to come down under the new ceiling first.
        c.cloneFromTemplate(new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 1, 2048L, null, null, null));

        assertOrder(t, "VM.set_VCPUs_at_startup", "VM.set_VCPUs_max");
        assertEquals("1", paramsOf(t, "VM.set_VCPUs_at_startup").get(2).asText());
        assertEquals("1", paramsOf(t, "VM.set_VCPUs_max").get(2).asText());
        assertEquals(1, t.vcpusMax);
        assertEquals(1, t.vcpusAtStartup);
        assertFalse(t.methods().contains("VM.destroy"), "a clone that sized cleanly must not be torn down");
    }

    @Test
    void cloneGrowingVcpusRaisesTheCeilingFirst() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");

        // The mirror image: at_startup first would try 0 < 4 <= 2 against the inherited ceiling.
        c.cloneFromTemplate(new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 4, 2048L, null, null, null));

        assertOrder(t, "VM.set_VCPUs_max", "VM.set_VCPUs_at_startup");
        assertEquals(4, t.vcpusMax);
        assertEquals(4, t.vcpusAtStartup);
    }

    @Test
    void cloneSeedsGuestDataIntoXenstoreBeforeReturning() {
        ScriptedTransport t = new ScriptedTransport();
        // A key the template already carries; the seed must merge onto it, not replace the whole map.
        t.xenstoreData = Map.of("mmio-hole-size", "4096");
        XapiClient c = new XapiClient(t, "root", "pw");

        c.cloneFromTemplate(
                new VmRef("OpaqueRef:tmpl"),
                new ProvisionSpec(
                        "agent",
                        2,
                        2048L,
                        null,
                        null,
                        null,
                        Map.of("url", "http://ci.example/", "name", "agent", "secret", "abc123")));

        // The seed is written after sizing (so a rejected size tears the clone down before any write) and
        // is a read-merge-write: get the inherited map, then set the union, keys under vm-data/jenkins/.
        assertOrder(t, "VM.set_memory_limits", "VM.get_xenstore_data", "VM.set_xenstore_data");
        JsonNode set = paramsOf(t, "VM.set_xenstore_data").get(2);
        assertEquals("http://ci.example/", set.get("vm-data/jenkins/url").asText());
        assertEquals("agent", set.get("vm-data/jenkins/name").asText());
        assertEquals("abc123", set.get("vm-data/jenkins/secret").asText());
        assertEquals("4096", set.get("mmio-hole-size").asText(), "an inherited xenstore key must survive the merge");
    }

    @Test
    void clearGuestSecretRemovesOnlyTheSecretKey() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");

        c.clearGuestSecret(new VmRef("OpaqueRef:vm"));

        // A per-key delete, not a read-merge-write: the secret key is named directly and url/name are
        // never mentioned, so they cannot be dropped. Removing an absent key is a no-op on the pool, so no
        // read is needed to guard it.
        assertFalse(t.methods().contains("VM.get_xenstore_data"), "a per-key delete needs no read");
        JsonNode params = paramsOf(t, "VM.remove_from_xenstore_data");
        assertEquals("OpaqueRef:vm", params.get(1).asText());
        assertEquals("vm-data/jenkins/secret", params.get(2).asText());
    }

    @Test
    void cloneWithNoGuestDataWritesNoXenstore() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");

        // The 6-arg spec carries an empty guest seed, so the clone must not touch xenstore at all.
        c.cloneFromTemplate(new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 2, 2048L, null, null, null));

        assertFalse(t.methods().contains("VM.get_xenstore_data"), "no seed means no xenstore read");
        assertFalse(t.methods().contains("VM.set_xenstore_data"), "no seed means no xenstore write");
    }

    @Test
    void cloneMarksTheOwningCloudInOtherConfig() {
        ScriptedTransport t = new ScriptedTransport();
        // Keys XCP-ng itself keeps on the record; the marker merges onto them rather than replacing them.
        t.otherConfig = Map.of("mac_seed", "9f4d-abcd", "base_template_name", "Debian Bookworm 12");
        XapiClient c = new XapiClient(t, "root", "pw");

        c.cloneFromTemplate(
                new VmRef("OpaqueRef:tmpl"),
                new ProvisionSpec("agent", 2, 2048L, null, null, null, Map.of(), "xcpng-lab"));

        // Stamped on the record itself, which is the whole point: tools/reaper.py selects on this key, and
        // a name-based selector is what left it unable to see a single plugin-provisioned VM.
        JsonNode set = paramsOf(t, "VM.set_other_config").get(2);
        assertEquals("xcpng-lab", set.get(XapiClient.OWNER_KEY).asText());
        assertEquals("9f4d-abcd", set.get("mac_seed").asText(), "an inherited other_config key must survive");
        assertEquals("Debian Bookworm 12", set.get("base_template_name").asText());
        // Before the VM can ever run: a clone that starts, then crashes before it is marked, is exactly the
        // leak the marker exists to make findable.
        assertOrder(t, "VM.set_other_config", "VM.set_memory_limits");
    }

    @Test
    void cloneWithNoOwnerTouchesNoOtherConfig() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");

        // The 6-arg spec carries no owner: a caller only sizing a clone marks nothing.
        c.cloneFromTemplate(new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 2, 2048L, null, null, null));

        assertFalse(t.methods().contains("VM.get_other_config"), "no owner means no other_config read");
        assertFalse(t.methods().contains("VM.set_other_config"), "no owner means no other_config write");
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
    void destroyWithDisksShutsARunningVmDownThroughTheAsyncTaskPath() {
        // #73: a synchronous hard_shutdown blocks server-side until the domain is down, so one
        // crossing the transport's 30s timeout failed the whole teardown as a leak while the
        // shutdown proceeded regardless. Same mismatch measured live for VM.start on this pool.
        ScriptedTransport t = new ScriptedTransport();
        t.powerState = "Running";
        XapiClient c = new XapiClient(t, "root", "pw");

        c.destroyWithDisks(new VmRef("OpaqueRef:vm-1"));

        List<String> methods = t.methods();
        assertTrue(methods.contains("Async.VM.hard_shutdown"), "the shutdown was not asynchronous");
        assertTrue(
                methods.indexOf("Async.VM.hard_shutdown") < methods.indexOf("task.get_status"),
                "the shutdown task went unawaited");
        assertTrue(
                methods.indexOf("task.get_status") < methods.indexOf("VM.destroy"),
                "the destroy must wait for the shutdown to settle");
    }

    @Test
    void destroyWithDisksDestroysASharedVdiOnlyOnce() {
        ScriptedTransport t = new ScriptedTransport();
        t.extraDisk = true; // two Disk VBDs
        t.sharedVdi = true; // both mapping to the same VDI ref
        XapiClient c = new XapiClient(t, "root", "pw");
        c.destroyWithDisks(new VmRef("OpaqueRef:vm-1")); // must not throw a false leak
        assertEquals(
                1,
                t.methods().stream().filter("VDI.destroy"::equals).count(),
                "a VDI shared by several VBDs must be destroyed exactly once");
    }

    // #145: a teardown that finds the VM already gone has reached its goal state, not failed. Two teardowns
    // race for the same VM in production (the completion reap and the idle net), and an operator running
    // `xe vm-destroy` beats both. Before this, HANDLE_INVALID propagated out of here, XcpngAgent._terminate
    // logged it at SEVERE and called recordLeakedVm, and the warm-pool maintainer then retried that
    // reference forever against a VM that no longer existed -- a permanent entry pointing at nothing, on a
    // pool that had actually returned to baseline.

    @Test
    void destroyWithDisksTreatsAnAlreadyGoneVmAsDestroyed() {
        ScriptedTransport t = new ScriptedTransport();
        t.handleInvalidOn = "VM.get_VBDs"; // the record vanished before this teardown could read it
        t.handleInvalidData = List.of("VM", "OpaqueRef:vm-1");
        XapiClient c = new XapiClient(t, "root", "pw");

        c.destroyWithDisks(new VmRef("OpaqueRef:vm-1")); // must not throw: there is nothing left to leak

        assertFalse(t.methods().contains("VDI.destroy"), "no disk was ever captured, so none can be destroyed");
    }

    @Test
    void destroyWithDisksStillReclaimsDisksWhenTheVmVanishesAfterTheyAreCaptured() {
        ScriptedTransport t = new ScriptedTransport();
        t.handleInvalidOn = "VM.destroy"; // another teardown won the race between capture and destroy
        t.handleInvalidData = List.of("VM", "OpaqueRef:vm-1");
        XapiClient c = new XapiClient(t, "root", "pw");

        c.destroyWithDisks(new VmRef("OpaqueRef:vm-1"));

        // VDIs outlive the VM record that referenced them, so swallowing the VM's disappearance must not
        // skip them. Returning early here would orphan exactly the disks this method exists to reclaim,
        // and nothing downstream could enumerate them again once the VM record was gone.
        assertEquals("OpaqueRef:vdi-disk", paramsOf(t, "VDI.destroy").get(1).asText());
    }

    @Test
    void destroyWithDisksDoesNotReportAnAlreadyGoneDiskAsLeaked() {
        ScriptedTransport t = new ScriptedTransport();
        t.handleInvalidOn = "VDI.destroy"; // whoever destroyed the VM took its disk too
        t.handleInvalidData = List.of("VDI", "OpaqueRef:vdi-disk");
        XapiClient c = new XapiClient(t, "root", "pw");

        // Must not throw "teardown leaked 1 VDI(s)": that is the same false alarm one level down.
        c.destroyWithDisks(new VmRef("OpaqueRef:vm-1"));
    }

    @Test
    void destroyWithDisksStillFailsWhenHandleInvalidNamesSomethingElse() {
        // The guard matches the reference under teardown, not the code alone. A HANDLE_INVALID about some
        // other object is a genuine failure, and swallowing it would trade a false leak report for a silent
        // real one -- strictly worse, because the reaper's whole value is telling the truth about leaks.
        ScriptedTransport t = new ScriptedTransport();
        t.handleInvalidOn = "VM.get_VBDs";
        t.handleInvalidData = List.of("VM", "OpaqueRef:some-other-vm");
        XapiClient c = new XapiClient(t, "root", "pw");

        HypervisorException e =
                assertThrows(HypervisorException.class, () -> c.destroyWithDisks(new VmRef("OpaqueRef:vm-1")));
        assertTrue(e.getMessage().contains("HANDLE_INVALID"), e.getMessage());
    }

    @Test
    void destroyWithDisksTreatsAVmThatVanishesDuringItsShutdownAsDestroyed() {
        // The other way the race lands. A halted VM fails the very first call, which the tests above cover;
        // a *running* one is shut down through an async task, and XAPI can accept the Async.VM.hard_shutdown
        // and then fail the task with HANDLE_INVALID. The exception then comes from the task's error info
        // rather than from a JSON-RPC error envelope, and it must carry the same code for the guard to read.
        ScriptedTransport t = new ScriptedTransport();
        t.powerState = "Running";
        t.taskStatus = "failure";
        t.taskErrorInfo = List.of("HANDLE_INVALID", "VM", "OpaqueRef:vm-1");
        XapiClient c = new XapiClient(t, "root", "pw");

        c.destroyWithDisks(new VmRef("OpaqueRef:vm-1")); // must not throw: the VM is gone, which is the goal

        assertTrue(t.methods().contains("Async.VM.hard_shutdown"), "the shutdown task path went unexercised");
        assertTrue(
                t.methods().contains("VDI.destroy"), "the disks captured before the shutdown must still be reclaimed");
    }

    @Test
    void destroyWithDisksStillFailsWhenAFailedTaskNamesSomethingElse() {
        // Same narrowness rule on the task path: the guard matches the reference under teardown, so a task
        // that failed about some other object stays a failure. Without this, a fix for the case above could
        // swallow every HANDLE_INVALID a task ever reports and nothing would notice.
        ScriptedTransport t = new ScriptedTransport();
        t.powerState = "Running";
        t.taskStatus = "failure";
        t.taskErrorInfo = List.of("HANDLE_INVALID", "VM", "OpaqueRef:some-other-vm");
        XapiClient c = new XapiClient(t, "root", "pw");

        HypervisorException e =
                assertThrows(HypervisorException.class, () -> c.destroyWithDisks(new VmRef("OpaqueRef:vm-1")));
        assertTrue(e.getMessage().contains("HANDLE_INVALID"), e.getMessage());
    }

    @Test
    void aFailedTaskCarriesItsCodeAndParamsStructurally() {
        // XAPI shapes error_info like a synchronous ErrorDescription: the code first, its parameters after.
        // Asserting the split here is what stops the async path degrading to a message-text match again.
        ScriptedTransport t = new ScriptedTransport();
        t.taskStatus = "failure";
        t.taskErrorInfo = List.of("VM_BAD_POWER_STATE", "OpaqueRef:vm-1", "Halted", "Running");
        XapiClient c = new XapiClient(t, "root", "pw");

        HypervisorException e = assertThrows(HypervisorException.class, () -> c.start(new VmRef("OpaqueRef:vm-1")));
        assertEquals("VM_BAD_POWER_STATE", e.getErrorCode());
        assertEquals(List.of("OpaqueRef:vm-1", "Halted", "Running"), e.getErrorParams());
    }

    @Test
    void aTaskThatFailsWithNoErrorInfoStillReportsNoCode() {
        // An empty error_info must not be mined for a code that is not there. Reporting none keeps the
        // already-gone guard from matching on an accident.
        ScriptedTransport t = new ScriptedTransport();
        t.taskStatus = "failure";
        t.taskErrorInfo = List.of();
        XapiClient c = new XapiClient(t, "root", "pw");

        HypervisorException e = assertThrows(HypervisorException.class, () -> c.start(new VmRef("OpaqueRef:vm-1")));
        assertEquals(null, e.getErrorCode());
        assertTrue(e.getErrorParams().isEmpty());
        assertTrue(e.getMessage().contains("failure"), e.getMessage());
    }

    @Test
    void anErrorEnvelopeCarriesItsCodeAndParamsStructurally() {
        // The already-gone guard reads these rather than the message text, so that a code quoted inside an
        // unrelated failure (a task's error info, say) cannot be mistaken for the failure itself.
        ScriptedTransport t = new ScriptedTransport();
        t.handleInvalidOn = "VM.get_power_state";
        t.handleInvalidData = List.of("VM", "OpaqueRef:vm-1");
        XapiClient c = new XapiClient(t, "root", "pw");

        HypervisorException e = assertThrows(HypervisorException.class, () -> c.state(new VmRef("OpaqueRef:vm-1")));
        assertEquals("HANDLE_INVALID", e.getErrorCode());
        assertEquals(List.of("VM", "OpaqueRef:vm-1"), e.getErrorParams());
    }

    @Test
    void aFailureWithNoBackendEnvelopeHasNoErrorCode() {
        ScriptedTransport t = new ScriptedTransport();
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e = assertThrows(
                HypervisorException.class,
                () -> c.cloneFromTemplate(
                        new VmRef("OpaqueRef:tmpl"), new ProvisionSpec("agent", 2, 2048L, null, "host-3", null)));
        assertEquals(null, e.getErrorCode(), "our own guards are not a backend error");
        assertTrue(e.getErrorParams().isEmpty());
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
                        "Halted",
                        VmState.HALTED,
                        "Running",
                        VmState.RUNNING,
                        "Paused",
                        VmState.PAUSED,
                        "Suspended",
                        VmState.SUSPENDED,
                        "wat",
                        VmState.UNKNOWN)
                .entrySet()) {
            t.powerState = e.getKey();
            assertEquals(e.getValue(), c.state(new VmRef("OpaqueRef:vm-1")));
        }
    }

    // The next three let login succeed and fail only on the target verb, so they exercise that verb's
    // own response handling rather than login's. The verb name in the message is asserted to prove it:
    // a transport that failed every call would trip inside login() and never reach the verb at all.

    @Test
    void anErrorEnvelopeBecomesAHypervisorException() {
        JsonRpcTransport t = body -> {
            JsonNode req = read(body);
            if (req.get("method").asText().equals("session.login_with_password")) {
                return envelope(req.get("id"), "OpaqueRef:session", null);
            }
            return envelope(req.get("id"), null, Map.of("message", "HANDLE_INVALID", "data", List.of("VM", "x")));
        };
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e = assertThrows(HypervisorException.class, () -> c.state(new VmRef("OpaqueRef:vm-1")));
        assertTrue(e.getMessage().contains("HANDLE_INVALID"), e.getMessage());
        assertTrue(e.getMessage().contains("VM.get_power_state"), e.getMessage());
    }

    @Test
    void aBareScalarResponseIsMalformed() {
        JsonRpcTransport t = body -> {
            JsonNode req = read(body);
            return req.get("method").asText().equals("session.login_with_password")
                    ? envelope(req.get("id"), "OpaqueRef:session", null)
                    : "5";
        };
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e = assertThrows(HypervisorException.class, c::ping);
        assertTrue(e.getMessage().contains("pool.get_all"), e.getMessage());
        assertTrue(e.getMessage().contains("expected a JSON object"), e.getMessage());
    }

    @Test
    void aNullResponseBodyIsSurfacedNotAnNpe() {
        JsonRpcTransport t = body -> {
            JsonNode req = read(body);
            return req.get("method").asText().equals("session.login_with_password")
                    ? envelope(req.get("id"), "OpaqueRef:session", null)
                    : null;
        };
        XapiClient c = new XapiClient(t, "root", "pw");
        HypervisorException e = assertThrows(HypervisorException.class, c::ping);
        assertTrue(e.getMessage().contains("pool.get_all"), e.getMessage());
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

        /**
         * What a failed task reports in {@code error_info}. Scriptable because a task is the other way a
         * teardown learns its object is already gone: XAPI can fail the {@code Async.*} call outright, or
         * accept it and fail the task. Leaving this fixed at an unrelated code would let a test cover the
         * first path and quietly claim the second.
         */
        List<String> taskErrorInfo = List.of("INTERNAL_ERROR", "boom");

        boolean vdiDestroyFails = false;
        boolean hostIsSlave = false;

        /**
         * Method that answers HANDLE_INVALID, standing in for a handle destroyed out from under this
         * teardown, and the class/reference pair XAPI puts in the error data. The reference is scripted
         * rather than assumed so a test can point the error at something other than the object under
         * teardown, which is what proves the already-gone guard is narrow.
         */
        String handleInvalidOn = null;

        List<String> handleInvalidData = List.of();
        boolean extraDisk = false;
        boolean sharedVdi = false;
        Map<String, String> xenstoreData = Map.of();

        /**
         * What the clone inherits in other_config from the template. XCP-ng keeps its own keys there, so a
         * marker write that replaced the map wholesale would silently drop them; leaving this empty by
         * default would let that regression pass.
         */
        Map<String, String> otherConfig = Map.of();
        /** Interrupt the calling thread when this method is posted, standing in for an interrupt landing mid-clone. */
        String interruptOn = null;
        /** The template's vCPU counts, which a clone inherits. The golden image on the lab pool has 2. */
        int vcpusMax = 2;

        int vcpusAtStartup = 2;

        @Override
        public String post(String body) throws IOException {
            // What HttpTransport does on an already-interrupted thread: HttpClient.send throws
            // InterruptedException, which it re-interrupts and rethrows as IOException. Modelling it here is
            // what makes the cleanup tests below real -- a transport that answered regardless would let a
            // destroy-on-an-interrupted-thread "succeed" and prove nothing.
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("interrupted");
            }
            JsonNode req = read(body);
            requests.add(req);
            String method = req.get("method").asText();
            if (method.equals(interruptOn)) {
                Thread.currentThread().interrupt();
            }
            JsonNode id = req.get("id");
            if (hostIsSlave && method.equals("VM.get_power_state")) {
                return envelope(id, null, Map.of("message", "HOST_IS_SLAVE", "data", List.of("192.168.1.99")));
            }
            if (vdiDestroyFails && method.equals("VDI.destroy")) {
                return envelope(id, null, Map.of("message", "VDI_IN_USE"));
            }
            if (method.equals(handleInvalidOn)) {
                return envelope(id, null, Map.of("message", "HANDLE_INVALID", "data", handleInvalidData));
            }
            // XAPI checks 0 < VCPUs_at_startup <= VCPUs_max on *every* write, against the value being set
            // and whatever the other one currently holds. Modelling it is what makes the sizing tests real:
            // accepting any value, as this transport used to, lets a clone "resize" in an order the pool
            // rejects outright.
            if (method.equals("VM.set_VCPUs_max") || method.equals("VM.set_VCPUs_at_startup")) {
                int value = Integer.parseInt(req.get("params").get(2).asText());
                boolean max = method.endsWith("_max");
                int wouldBeMax = max ? value : vcpusMax;
                int wouldBeAtStartup = max ? vcpusAtStartup : value;
                if (wouldBeAtStartup <= 0 || wouldBeAtStartup > wouldBeMax) {
                    return envelope(
                            id,
                            null,
                            Map.of(
                                    "message",
                                    "INVALID_VALUE",
                                    "data",
                                    List.of(
                                            "VCPU values must satisfy: 0 < VCPUs_at_startup ≤ VCPUs_max",
                                            String.valueOf(value))));
                }
                vcpusMax = wouldBeMax;
                vcpusAtStartup = wouldBeAtStartup;
            }
            Object result =
                    switch (method) {
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
                        case "Async.VM.hard_shutdown" -> "OpaqueRef:task-hard-shutdown";
                        // Answering the synchronous form would humour the exact regression #73
                        // removes; a fake more agreeable than the pool hides the bug (see the
                        // repo rule that came out of #24 and #42).
                        case "VM.hard_shutdown" ->
                            throw new AssertionError("synchronous VM.hard_shutdown: use the async task path");
                        case "task.get_status" -> taskStatus;
                        // Clone-like tasks wrap a hex OpaqueRef; void tasks (start, clean_shutdown) settle
                        // with an empty result. Returning a ref for everything would hide the void-task bug.
                        case "task.get_result" ->
                            req.get("params").get(1).asText().contains("clone")
                                    ? "<value>OpaqueRef:1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d</value>"
                                    : "";
                        case "task.get_error_info" -> taskErrorInfo;
                        case "VM.get_VCPUs_max" -> vcpusMax;
                        case "VM.get_xenstore_data" -> xenstoreData;
                        case "VM.get_other_config" -> otherConfig;
                        case "VM.get_power_state" -> powerState;
                        case "VM.get_guest_metrics" -> "OpaqueRef:gm";
                        case "VM_guest_metrics.get_networks" -> Map.of("0/ip", "192.168.1.50");
                        case "VM.get_VBDs" ->
                            extraDisk
                                    ? List.of("OpaqueRef:vbd-disk", "OpaqueRef:vbd-disk2", "OpaqueRef:vbd-cd")
                                    : List.of("OpaqueRef:vbd-disk", "OpaqueRef:vbd-cd");
                        case "VBD.get_type" -> req.get("params").get(1).asText().contains("cd") ? "CD" : "Disk";
                        case "VBD.get_VDI" -> {
                            String vbd = req.get("params").get(1).asText();
                            // Distinct VDI per disk VBD so a two-disk clone reads as two disks; sharedVdi maps
                            // both disk VBDs to the same ref to exercise the dedupe path.
                            yield (sharedVdi || !vbd.contains("disk2")) ? "OpaqueRef:vdi-disk" : "OpaqueRef:vdi-disk2";
                        }
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
