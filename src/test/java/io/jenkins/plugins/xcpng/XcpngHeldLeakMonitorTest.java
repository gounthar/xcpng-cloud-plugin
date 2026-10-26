package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

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

    /**
     * Forget removes the one entry asked for. The fixture carries the three neighbours a looser match would also
     * take: another held VM in the same cloud, the same ref held in another cloud, and an ordinary entry the
     * banner does not list, which the second POST names and must leave alone.
     */
    @Test
    void forgetRemovesExactlyTheNamedHeldEntry(JenkinsRule r) throws Exception {
        lockDown(r);
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng-lab", XcpngLeakedVm.legacy("vm-a").asHeld());
        store.record("xcpng-lab", XcpngLeakedVm.legacy("vm-b").asHeld());
        store.record("xcpng-lab", XcpngLeakedVm.legacy("vm-c"));
        store.record("gone-cloud", XcpngLeakedVm.legacy("vm-a").asHeld());
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");

        forget(r, alice, "xcpng-lab", "vm-a");
        forget(r, alice, "xcpng-lab", "vm-c");

        assertEquals(Set.of("vm-b", "vm-c"), store.refs("xcpng-lab"));
        assertEquals(Set.of("vm-a"), store.refs("gone-cloud"));
    }

    /** A POST missing a field, or naming nothing recorded, changes nothing and is not a server error. */
    @Test
    void forgetWithoutAMatchChangesNothing(JenkinsRule r) throws Exception {
        lockDown(r);
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng-lab", XcpngLeakedVm.legacy("vm-a").asHeld());
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");

        assertEquals(
                200,
                post(r, alice, "forget", new NameValuePair("vmRef", "vm-a")).getStatusCode());
        forget(r, alice, "xcpng-lab", "vm-unknown");
        forget(r, alice, "no-such-cloud", "vm-a");

        assertEquals(Set.of("vm-a"), store.refs("xcpng-lab"));
    }

    /**
     * Over HTTP a GET is a 405 even for the administrator and a bystander's POST is a 403. Core's monitor proxy
     * already refuses the bystander before the method runs, so the direct call as the bystander is what would go
     * red if the method's own permission check were removed.
     */
    @Test
    void forgetTakesAnAdministratorsPostOnly(JenkinsRule r) throws Exception {
        lockDown(r);
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng-lab", XcpngLeakedVm.legacy("vm-a").asHeld());

        r.createWebClient().login("alice").assertFails(monitor(r).getUrl() + "/forget?cloud=xcpng-lab&vmRef=vm-a", 405);
        JenkinsRule.WebClient bob = r.createWebClient().login("bob");
        bob.setThrowExceptionOnFailingStatusCode(false);
        assertEquals(403, forget(r, bob, "xcpng-lab", "vm-a").getStatusCode());
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> monitor(r).doForget("xcpng-lab", "vm-a"));
        }

        assertEquals(Set.of("vm-a"), store.refs("xcpng-lab"));
    }

    /** The button on the banner works as rendered: a plain form, so this also proves the crumb reaches it. */
    @Test
    void theBannerButtonForgetsTheEntry(JenkinsRule r) throws Exception {
        lockDown(r);
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng-lab", XcpngLeakedVm.legacy("vm-held-330").asHeld());
        HtmlPage page = r.createWebClient().login("alice").goTo("manage/");
        List<HtmlForm> forms = page.getByXPath("//form[contains(@class, 'xcpng-forget')]");
        assertEquals(1, forms.size(), page.asNormalizedText());

        r.submit(forms.get(0));

        assertTrue(store.refs("xcpng-lab").isEmpty(), "the entry survived its own Forget button");
        assertFalse(monitor(r).isActivated());
    }

    /** A realm with an administrator and a bystander who can see Jenkins and nothing else. */
    private static void lockDown(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("alice")
                .grant(Jenkins.READ)
                .everywhere()
                .to("bob"));
    }

    private static WebResponse forget(JenkinsRule r, JenkinsRule.WebClient wc, String cloud, String vmRef)
            throws Exception {
        return post(r, wc, "forget", new NameValuePair("cloud", cloud), new NameValuePair("vmRef", vmRef));
    }

    /** POST with the CSRF crumb, the way the browser sends it; see XcpngFormValidationGuardTest#post. */
    private static WebResponse post(JenkinsRule r, JenkinsRule.WebClient wc, String action, NameValuePair... fields)
            throws Exception {
        WebRequest req = new WebRequest(
                new java.net.URI(r.getURL() + monitor(r).getUrl() + "/" + action).toURL(), HttpMethod.POST);
        wc.addCrumb(req);
        List<NameValuePair> params = new ArrayList<>(req.getRequestParameters());
        params.addAll(List.of(fields));
        req.setRequestParameters(params);
        return wc.getPage(req).getWebResponse();
    }
}
