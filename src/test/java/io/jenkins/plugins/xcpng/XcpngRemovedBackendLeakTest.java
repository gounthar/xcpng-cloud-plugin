package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
 * Leaked VMs recorded over the removed XAPI backend (#330): an agent still running at the upgrade, torn down once
 * the new release could no longer open its connection. No sweep can reach such a VM, and giving up on its entry
 * used to leave nothing in Jenkins naming it, so the sweep keeps it and {@link XcpngRemovedBackendMonitor} lists it
 * until an administrator forgets it.
 *
 * <p>Every keep is paired with an XO entry on a connection that cannot open either, which must still be given up:
 * a sweep that kept everything would pass the keep on its own.
 */
@WithJenkins
class XcpngRemovedBackendLeakTest {

    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

    private static final String XAPI_VM = "OpaqueRef:xapi-era-1";
    private static final String XO_VM = "vm/xo/deleted-credential";

    private static XcpngCloud cloudBackedBy(JenkinsRule r, FakeHypervisorClient fake) {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://xo.example.test", "cred", null, 2, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    /** As a teardown records it: the agent's own XAPI connection, and its name. */
    private static XcpngLeakedVm xapiLeak(String vmRef, String nodeName) {
        return XcpngLeakedVm.of(vmRef, XcpngBackend.XAPI, "https://xapi-pool.example.test", "root-password", null)
                .named(nodeName);
    }

    /** An XO entry whose credential is gone: the production path refuses to open it, as it refuses XAPI. */
    private static XcpngLeakedVm xoLeakWithADeletedCredential() {
        return XcpngLeakedVm.of(XO_VM, XcpngBackend.XO, "https://old-xo.example.test", "cred-deleted", null);
    }

    private static List<String> severeLinesDuring(Runnable action) {
        List<String> lines = new ArrayList<>();
        Logger logger = Logger.getLogger(XcpngCloud.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE && record.getMessage() != null) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return lines;
    }

    // ---- The sweep keeps them ----

    /**
     * The case #330 is about, on the production path: no seam, so the XAPI connection is refused by
     * {@code openClient} exactly as on a real controller. Two sweeps, so the log line is checked for being said once.
     */
    @Test
    void anXapiLeakIsKeptAndReportedOnceWithWhatAPersonNeeds(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        cloud.recordLeakedVm(xapiLeak(XAPI_VM, "xcpng-linux-1a2b3c4d"));
        cloud.recordLeakedVm(xoLeakWithADeletedCredential());

        List<String> severe = severeLinesDuring(() -> {
            cloud.sweepLeakedVms();
            cloud.sweepLeakedVms();
        });

        assertEquals(Set.of(XAPI_VM), cloud.leakedVmRefs(), "the XAPI entry stays; the XO control is given up");
        assertTrue(fake.calls().isEmpty(), "neither ref may reach the cloud's own connection: " + fake.calls());
        List<String> kept =
                severe.stream().filter(line -> line.contains(XAPI_VM)).toList();
        assertEquals(1, kept.size(), "said once per controller start, not every sweep: " + severe);
        String line = kept.get(0);
        assertTrue(line.contains("agent xcpng-linux-1a2b3c4d"), line);
        assertTrue(line.contains("https://xapi-pool.example.test"), line);
        assertTrue(line.contains("tools/reaper.py --cloud xcpng"), line);
        assertFalse(line.contains("Giving up"), line);
    }

    /**
     * A cloud not yet switched to XO still names XAPI, so its own XAPI entries match its connection. That path
     * opens the cloud's own connection, which an operator can repair, and only warned: the entry has to get the
     * same SEVERE naming the reaper, since no repair of a cloud still on XAPI will ever open it.
     */
    @Test
    void anXapiLeakOnACloudStillOnXapiGetsTheSameNotice(JenkinsRule r) {
        XcpngCloud cloud =
                new XcpngCloud("xcpng", "https://xapi-pool.example.test", "root-password", null, 2, List.of());
        cloud.setBackend(XcpngBackend.XAPI);
        r.jenkins.clouds.add(cloud);
        XcpngLeakedVm leak = XcpngLeakedVm.of(XAPI_VM, cloud).named("xcpng-linux-1a2b3c4d");
        assertTrue(leak.sameConnectionAs(cloud), "control: the entry is on the cloud's own connection");
        cloud.recordLeakedVm(leak);

        List<String> severe = severeLinesDuring(() -> {
            cloud.sweepLeakedVms();
            cloud.sweepLeakedVms();
        });

        assertEquals(Set.of(XAPI_VM), cloud.leakedVmRefs());
        List<String> kept =
                severe.stream().filter(line -> line.contains(XAPI_VM)).toList();
        assertEquals(1, kept.size(), "said once, as on a cloud already moved to XO: " + severe);
        assertTrue(kept.get(0).contains("tools/reaper.py --cloud xcpng"), kept.get(0));
    }

    /**
     * Kept, but not against Max instances: the cloud has been moved to XO and provisions again, and an entry no
     * sweep can ever clear would hold its slot for good. It counts as needing a person instead, which is what
     * {@link XcpngLeakCapacityMonitor} reports.
     */
    @Test
    void anXapiLeakNeedsAPersonAndHoldsNoSlot(JenkinsRule r) {
        XcpngCloud cloud = cloudBackedBy(r, new FakeHypervisorClient("jenkins-golden-debian"));
        cloud.recordLeakedVm(xapiLeak(XAPI_VM, "xcpng-linux-1a2b3c4d"));
        cloud.recordLeakedVm(XcpngLeakedVm.of("vm/xo/retrying", cloud));

        assertEquals(1, cloud.leakedHoldingCapacity(), "control: the XO entry the sweep retries holds a slot");
        assertEquals(1, cloud.leakedNeedingAPerson(), "the XAPI entry needs a person");
    }

    /**
     * A cloud deleted after the upgrade leaves its entries to the orphan sweep (#314), which takes the same
     * recorded-connection path, so the keep has to hold there too.
     */
    @Test
    void anXapiLeakOfARemovedCloudIsKeptByTheOrphanSweep(JenkinsRule r) {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("gone-cloud", xapiLeak(XAPI_VM, "xcpng-linux-1a2b3c4d"));
        store.record("gone-cloud", xoLeakWithADeletedCredential());

        XcpngCloud.sweepOrphanedLeaks();

        assertEquals(Set.of(XAPI_VM), store.refs("gone-cloud"), "the XAPI entry stays; the XO control is given up");
    }

    // ---- The monitor lists them ----

    private static XcpngRemovedBackendMonitor monitor(JenkinsRule r) {
        return r.jenkins.getExtensionList(XcpngRemovedBackendMonitor.class).get(0);
    }

    /** Only removed-backend entries raise it: an XO leaked entry is the sweep's, and is recorded first as the control. */
    @Test
    void onlyRemovedBackendEntriesRaiseTheMonitor(JenkinsRule r) {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng", XcpngLeakedVm.legacy("55703ef8-ca33-ee80-e0d1-f9aee081ab7e"));
        store.record("xcpng", XcpngLeakedVm.legacy("vm-held").asHeld());
        assertFalse(monitor(r).isActivated(), "control: XO entries, held or not, must not raise it");

        store.record("xcpng", xapiLeak(XAPI_VM, "xcpng-linux-1a2b3c4d"));

        assertTrue(monitor(r).isActivated());
        assertEquals(
                List.of(XAPI_VM),
                monitor(r).getLeaked().get("xcpng").stream()
                        .map(XcpngLeakedVm::getVmRef)
                        .toList());
    }

    /** The view renders and names what a person looks for on the pool: the agent, the ref, and the reaper. */
    @Test
    void theBannerNamesTheAgentAndTheReaper(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng", xapiLeak(XAPI_VM, "xcpng-linux-1a2b3c4d"));
        store.record("xcpng", XcpngLeakedVm.legacy("OpaqueRef:unnamed-1"));

        String text = r.createWebClient().goTo("manage/").asNormalizedText();

        assertTrue(text.contains("xcpng-linux-1a2b3c4d (VM " + XAPI_VM + ")"), text);
        assertTrue(text.contains("on https://xapi-pool.example.test"), text);
        assertTrue(text.contains("VM OpaqueRef:unnamed-1, agent name not recorded"), text);
        assertTrue(text.contains("Cloud xcpng:"), text);
        assertTrue(text.contains("tools/reaper.py --cloud xcpng"), text);
    }

    /**
     * Forget removes the one entry asked for. The neighbours a looser match would also take: another XAPI entry in
     * the same cloud, the same ref in another cloud, and an XO entry with the same ref, which the banner does not
     * list and which must stay the sweep's.
     */
    @Test
    void forgetRemovesExactlyTheNamedRemovedBackendEntry(JenkinsRule r) throws Exception {
        lockDown(r);
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng", xapiLeak(XAPI_VM, "agent-a"));
        store.record("xcpng", xapiLeak("OpaqueRef:xapi-era-2", "agent-b"));
        store.record("xcpng", XcpngLeakedVm.of(XAPI_VM, XcpngBackend.XO, "https://xo.example.test", "cred", null));
        store.record("gone-cloud", xapiLeak(XAPI_VM, "agent-a"));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");

        forget(r, alice, "xcpng", XAPI_VM);

        assertEquals(2, store.entries("xcpng").size(), store.entries("xcpng").toString());
        assertTrue(
                store.entries("xcpng").stream()
                        .anyMatch(vm -> vm.getVmRef().equals(XAPI_VM) && vm.getBackend() == XcpngBackend.XO),
                "the XO entry with the same ref is not the banner's to forget: " + store.entries("xcpng"));
        assertEquals(Set.of(XAPI_VM, "OpaqueRef:xapi-era-2"), store.refs("xcpng"));
        assertEquals(Set.of(XAPI_VM), store.refs("gone-cloud"));
    }

    /** A POST missing a field, or naming nothing recorded, changes nothing and is not a server error. */
    @Test
    void forgetWithoutAMatchChangesNothing(JenkinsRule r) throws Exception {
        lockDown(r);
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng", xapiLeak(XAPI_VM, "agent-a"));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");

        assertEquals(200, post(r, alice, new NameValuePair("vmRef", XAPI_VM)).getStatusCode());
        forget(r, alice, "xcpng", "OpaqueRef:unknown");
        forget(r, alice, "no-such-cloud", XAPI_VM);

        assertEquals(Set.of(XAPI_VM), store.refs("xcpng"));
    }

    /**
     * A GET is a 405 and a bystander's POST a 403. Core's monitor proxy refuses the bystander before the method
     * runs, so the direct call is what goes red if the method's own permission check is removed.
     */
    @Test
    void forgetTakesAnAdministratorsPostOnly(JenkinsRule r) throws Exception {
        lockDown(r);
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng", xapiLeak(XAPI_VM, "agent-a"));

        r.createWebClient()
                .login("alice")
                .assertFails(monitor(r).getUrl() + "/forget?cloud=xcpng&vmRef=" + XAPI_VM, 405);
        JenkinsRule.WebClient bob = r.createWebClient().login("bob");
        bob.setThrowExceptionOnFailingStatusCode(false);
        assertEquals(403, forget(r, bob, "xcpng", XAPI_VM).getStatusCode());
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> monitor(r).doForget("xcpng", XAPI_VM));
        }

        assertEquals(Set.of(XAPI_VM), store.refs("xcpng"));
    }

    /** The button on the banner works as rendered, crumb included, and the monitor clears with its last entry. */
    @Test
    void theBannerButtonForgetsTheEntry(JenkinsRule r) throws Exception {
        lockDown(r);
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng", xapiLeak(XAPI_VM, "agent-a"));
        HtmlPage page = r.createWebClient().login("alice").goTo("manage/");
        List<HtmlForm> forms = page.getByXPath("//form[contains(@class, 'xcpng-forget')]");
        assertEquals(1, forms.size(), page.asNormalizedText());

        r.submit(forms.get(0));

        assertTrue(store.refs("xcpng").isEmpty(), "the entry survived its own Forget button");
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
        return post(r, wc, new NameValuePair("cloud", cloud), new NameValuePair("vmRef", vmRef));
    }

    /** POST to the Forget action with the CSRF crumb, the way the browser sends it. */
    private static WebResponse post(JenkinsRule r, JenkinsRule.WebClient wc, NameValuePair... fields) throws Exception {
        WebRequest req =
                new WebRequest(new java.net.URI(r.getURL() + monitor(r).getUrl() + "/forget").toURL(), HttpMethod.POST);
        wc.addCrumb(req);
        List<NameValuePair> params = new ArrayList<>(req.getRequestParameters());
        params.addAll(List.of(fields));
        req.setRequestParameters(params);
        return wc.getPage(req).getWebResponse();
    }
}
