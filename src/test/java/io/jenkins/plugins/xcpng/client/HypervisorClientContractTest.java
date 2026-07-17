package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises the client seam through {@link FakeHypervisorClient}. Proves the contract compiles and
 * behaves, and pins the verb sequence a provisioning cycle produces so slice 4 can assert against it.
 */
class HypervisorClientContractTest {

    @Test
    void valueTypesRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new VmRef(" "));
        assertThrows(IllegalArgumentException.class, () -> new ProvisionSpec("a", 0, 1L, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ProvisionSpec("a", 1, 0L, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ProvisionSpec(" ", 1, 1L, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ProvisionSpec("a", 1, 1L, -1L, null, null));
    }

    @Test
    void resolveTemplateFailsForUnknownName() {
        HypervisorClient client = new FakeHypervisorClient("jenkins-golden-debian");
        assertThrows(HypervisorException.class, () -> client.resolveTemplate("does-not-exist"));
    }

    @Test
    void aProvisioningCycleClonesStartsThenDestroysWithDisks() {
        FakeHypervisorClient client = new FakeHypervisorClient("jenkins-golden-debian");

        VmRef template = client.resolveTemplate("jenkins-golden-debian");
        ProvisionSpec spec = new ProvisionSpec("agent-1", 2, 2L * 1024 * 1024 * 1024, null, null, null);
        VmRef vm = client.cloneFromTemplate(template, spec);
        client.start(vm);
        assertEquals(VmState.RUNNING, client.state(vm));
        client.destroyWithDisks(vm);
        assertEquals(VmState.UNKNOWN, client.state(vm), "destroyed VM has no state");

        List<String> calls = client.calls();
        int cloneAt = indexOfPrefix(calls, "cloneFromTemplate:");
        int startAt = calls.indexOf("start:" + vm.value());
        int destroyAt = calls.indexOf("destroyWithDisks:" + vm.value());
        assertTrue(cloneAt >= 0, "clone recorded");
        assertTrue(startAt >= 0, "start recorded");
        assertTrue(destroyAt >= 0, "destroy recorded");
        assertTrue(cloneAt < startAt, "clone before start");
        assertTrue(startAt < destroyAt, "start before destroy");
    }

    @Test
    void ipIsEmptyWithoutGuestToolsAndPresentWhenReported() {
        FakeHypervisorClient noTools = new FakeHypervisorClient("t");
        VmRef vm = noTools.cloneFromTemplate(noTools.resolveTemplate("t"), spec());
        noTools.start(vm);
        assertTrue(noTools.primaryIpAddress(vm).isEmpty(), "no guest tools, no address");

        FakeHypervisorClient withTools = new FakeHypervisorClient("t").reportIpOnStart("192.168.1.42");
        VmRef vm2 = withTools.cloneFromTemplate(withTools.resolveTemplate("t"), spec());
        assertTrue(withTools.primaryIpAddress(vm2).isEmpty(), "halted VM reports nothing yet");
        withTools.start(vm2);
        assertEquals(Optional.of("192.168.1.42"), withTools.primaryIpAddress(vm2));
    }

    @Test
    void pingSurfacesFailure() {
        assertThrows(
                HypervisorException.class,
                () -> new FakeHypervisorClient().failPing().ping());
        new FakeHypervisorClient().ping(); // does not throw on success
    }

    @Test
    void closeIsRecorded() {
        FakeHypervisorClient client = new FakeHypervisorClient();
        client.close();
        assertFalse(client.calls().isEmpty());
        assertEquals("close", client.calls().get(client.calls().size() - 1));
    }

    private static ProvisionSpec spec() {
        return new ProvisionSpec("agent", 1, 1024L * 1024 * 1024, null, null, null);
    }

    private static int indexOfPrefix(List<String> calls, String prefix) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
