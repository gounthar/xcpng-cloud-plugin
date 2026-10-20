package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The banner for held leaked VMs (#306). A held VM that really stopped is never destroyed automatically, so this
 * monitor is how it stays visible after the sweep's one SEVERE line has scrolled away or the controller restarted.
 */
@WithJenkins
class XcpngHeldLeakMonitorTest {

    private static XcpngHeldLeakMonitor monitor(JenkinsRule r) {
        return r.jenkins.getExtensionList(XcpngHeldLeakMonitor.class).get(0);
    }

    /**
     * Only held entries raise it. An ordinary leaked entry is recorded first as the control: without it, a monitor
     * listing every leaked VM passes, since the sweep retries those on its own and nobody needs a banner for them.
     */
    @Test
    void onlyHeldEntriesRaiseTheMonitor(JenkinsRule r) {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng-lab", XcpngLeakedVm.legacy("vm-ordinary"));
        assertFalse(monitor(r).isActivated(), "control: an ordinary leaked entry must not raise the monitor");

        store.record("xcpng-lab", XcpngLeakedVm.legacy("vm-held").asHeld());
        store.record("gone-cloud", XcpngLeakedVm.legacy("vm-orphan").asHeld());

        assertTrue(monitor(r).isActivated());
        Map<String, List<XcpngLeakedVm>> held = monitor(r).getHeld();
        // A cloud that is not configured is listed too: a renamed or removed cloud is when nothing else names it.
        assertEquals(List.of("gone-cloud", "xcpng-lab"), List.copyOf(held.keySet()));
        assertEquals(
                List.of("vm-held"),
                held.get("xcpng-lab").stream().map(XcpngLeakedVm::getVmRef).toList());
    }

    /** It clears itself once the entry leaves the store, which is what a sweep finding the VM gone does. */
    @Test
    void theMonitorClearsWhenTheHeldEntryIsDropped(JenkinsRule r) {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        XcpngLeakedVm vm = XcpngLeakedVm.legacy("vm-held").asHeld();
        store.record("xcpng-lab", vm);
        assertTrue(monitor(r).isActivated(), "the fixture must raise the monitor, or the assertion below is vacuous");

        store.drop("xcpng-lab", List.of(vm));

        assertFalse(monitor(r).isActivated());
    }

    /** The view renders, names the VM and the dry-run command, and parses as Jelly with its MessageFormat argument. */
    @Test
    void theBannerNamesTheVmAndTheDryRun(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore.get()
                .record("xcpng-lab", XcpngLeakedVm.legacy("vm-held-306").asHeld());

        HtmlPage page = r.createWebClient().goTo("manage/");
        String text = page.asNormalizedText();

        assertTrue(text.contains("vm-held-306"), text);
        assertTrue(text.contains("Cloud xcpng-lab:"), text);
        assertTrue(text.contains("tools/reaper.py --cloud xcpng-lab --dom0-check"), text);
    }
}
