package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The banner for a cap held by leaked VMs (#312). A cloud whose slots are all leaked provisions nothing and logs
 * nothing at the moment builds stop being served, so this is where an administrator finds out why.
 */
@WithJenkins
class XcpngLeakCapacityMonitorTest {

    private static XcpngLeakCapacityMonitor monitor(JenkinsRule r) {
        return r.jenkins.getExtensionList(XcpngLeakCapacityMonitor.class).get(0);
    }

    private static XcpngCloud cloud(String name) {
        return new XcpngCloud(
                name,
                "https://xo.example.test",
                "cred",
                "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                2,
                List.of());
    }

    /**
     * Raised by a leak of a configured cloud, and only that. The control is a leak recorded under a cloud that is
     * not configured: it holds no cap anyone provisions against, so a banner for it would be noise.
     */
    @Test
    void aLeakOfAConfiguredCloudRaisesTheMonitor(JenkinsRule r) {
        r.jenkins.clouds.add(cloud("xcpng-lab"));
        XcpngLeakedVmStore.get().record("gone-cloud", XcpngLeakedVm.legacy("vm-orphan"));
        assertFalse(monitor(r).isActivated(), "control: a leak of an unconfigured cloud holds no cap");

        XcpngLeakedVmStore.get().record("xcpng-lab", XcpngLeakedVm.legacy("vm-leaked-312"));

        assertTrue(monitor(r).isActivated());
        assertEquals(
                List.of("Cloud xcpng-lab: 1 leaked VM(s) count against Max instances 2"),
                monitor(r).getRows());
    }

    @Test
    void theMonitorClearsWhenTheLeakIsDropped(JenkinsRule r) {
        r.jenkins.clouds.add(cloud("xcpng-lab"));
        XcpngLeakedVm vm = XcpngLeakedVm.legacy("vm-leaked-312");
        XcpngLeakedVmStore.get().record("xcpng-lab", vm);
        assertTrue(monitor(r).isActivated(), "the fixture must raise the monitor, or the assertion below is vacuous");

        XcpngLeakedVmStore.get().drop("xcpng-lab", List.of(vm));

        assertFalse(monitor(r).isActivated());
    }

    /**
     * A leak no sweep will reclaim gets its own line, since it no longer counts against the cap but still sits on
     * the pool. A held entry is the fixture: it needs no backend to be in that state.
     */
    @Test
    void aLeakOnlyAPersonCanRemoveIsListedApart(JenkinsRule r) {
        XcpngCloud cloud = cloud("xcpng-lab");
        r.jenkins.clouds.add(cloud);
        XcpngLeakedVmStore.get().record("xcpng-lab", XcpngLeakedVm.legacy("vm-leaked-312"));
        XcpngLeakedVmStore.get()
                .record("xcpng-lab", XcpngLeakedVm.of("vm-held-306", cloud).asHeld());

        assertEquals(
                List.of(
                        "Cloud xcpng-lab: 1 leaked VM(s) count against Max instances 2",
                        "Cloud xcpng-lab: 1 leaked VM(s) that cleanup will not delete on its own; they do not count"
                                + " against Max instances and stay on the pool until removed by hand"),
                monitor(r).getRows());
    }

    /** The view renders and parses as Jelly, with the row's MessageFormat arguments filled in. */
    @Test
    void theBannerNamesTheCloudAndTheCount(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(cloud("xcpng-lab"));
        XcpngLeakedVmStore.get().record("xcpng-lab", XcpngLeakedVm.legacy("vm-leaked-312"));

        HtmlPage page = r.createWebClient().goTo("manage/");
        String text = page.asNormalizedText();

        assertTrue(text.contains("Cloud xcpng-lab: 1 leaked VM(s) count against Max instances 2"), text);
        assertTrue(text.contains("counts against the cloud Max instances"), text);
    }
}
