package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import io.jenkins.plugins.xcpng.client.VmRef;
import io.jenkins.plugins.xcpng.client.VmState;
import io.jenkins.plugins.xcpng.client.XapiEraVm;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The Reclaim button on the removed-backend banner, end to end against the fake client (#364): the preview changes
 * nothing, the confirmation takes the VM over and hands it to the held-entry sweep, and every way the checks can fail
 * leaves the entry listed with the reason shown.
 */
@WithJenkins
class XcpngXapiEraReclaimFlowTest {

    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

    private static final String CLOUD = "xcpng";
    private static final String REF = "OpaqueRef:47d08d21-17c9-a450-2738-546217643c7d";
    private static final String UUID = "d3aa4c31-2d52-6ab0-e766-157f524ea8b4";
    private static final String NODE = "xcpng-linux-60cd9585";

    private static XcpngCloud cloudBackedBy(JenkinsRule r, FakeHypervisorClient fake) {
        XcpngCloud cloud = new XcpngCloud(CLOUD, "https://xo.example.test", "cred", null, 2, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static XcpngLeakedVm entry() {
        return XcpngLeakedVm.of(REF, XcpngBackend.XAPI, "https://xcp-ng-hhpfmhok", "xcpng-lab-xapi", null)
                .named(NODE);
    }

    private static XapiEraVm found(String name) {
        return new XapiEraVm(new VmRef(UUID), UUID, REF, name, "pool-1", VmState.RUNNING, CLOUD, List.of());
    }

    private static FakeHypervisorClient fakeFinding(String name) {
        return new FakeHypervisorClient("jenkins-golden-debian").withXapiEraVm(REF, found(name));
    }

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

    private static XcpngRemovedBackendMonitor monitor(JenkinsRule r) {
        return r.jenkins.getExtensionList(XcpngRemovedBackendMonitor.class).get(0);
    }

    private static Page post(JenkinsRule r, JenkinsRule.WebClient wc, String action, NameValuePair... fields)
            throws Exception {
        WebRequest req = new WebRequest(
                new java.net.URI(r.getURL() + monitor(r).getUrl() + "/" + action).toURL(), HttpMethod.POST);
        wc.addCrumb(req);
        List<NameValuePair> params = new ArrayList<>(req.getRequestParameters());
        params.addAll(List.of(fields));
        req.setRequestParameters(params);
        return wc.getPage(req);
    }

    private static Page preview(JenkinsRule r, JenkinsRule.WebClient wc) throws Exception {
        return post(r, wc, "reclaim", new NameValuePair("cloud", CLOUD), new NameValuePair("vmRef", REF));
    }

    private static Page confirm(JenkinsRule r, JenkinsRule.WebClient wc, String uuid) throws Exception {
        return post(
                r,
                wc,
                "reclaimConfirm",
                new NameValuePair("cloud", CLOUD),
                new NameValuePair("vmRef", REF),
                new NameValuePair("uuid", uuid));
    }

    private static String text(Page page) {
        return page.getWebResponse().getContentAsString();
    }

    private static boolean adopted(FakeHypervisorClient fake) {
        return fake.calls().stream().anyMatch(c -> c.startsWith("adoptXapiEraVm:"));
    }

    @Test
    void theBannerOffersReclaimNextToForget(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore.get().record(CLOUD, entry());

        String page = r.createWebClient().goTo("manage/").asXml();

        assertTrue(page.contains("xcpng-reclaim"), page);
        assertTrue(page.contains("xcpng-forget"), page);
    }

    /** The preview shows what was found and offers the confirmation, and changes nothing. */
    @Test
    void thePreviewShowsTheVmAndChangesNothing(JenkinsRule r) throws Exception {
        lockDown(r);
        FakeHypervisorClient fake = fakeFinding(NODE);
        cloudBackedBy(r, fake);
        XcpngLeakedVmStore.get().record(CLOUD, entry());

        String page = text(preview(r, r.createWebClient().login("alice")));

        assertTrue(page.contains(UUID), page);
        assertTrue(page.contains("https://xcp-ng-hhpfmhok"), "the stored pool, for the administrator to compare");
        assertTrue(page.contains("reclaimConfirm"), page);
        assertTrue(fake.calls().contains("lookUpXapiEraVm:" + REF), fake.calls().toString());
        assertFalse(adopted(fake), fake.calls().toString());
        assertEquals(Set.of(REF), XcpngLeakedVmStore.get().refs(CLOUD));
    }

    /**
     * Confirmed, the VM is taken over and its entry becomes a held one on the cloud's connection, named as before.
     * While the pool reads it halted it stays held (#48); once it reads running, a sweep destroys it by its XO id
     * under this cloud's name, which is what refuseUnlessOwned checks.
     *
     * <p>The pool reads it halted from the start so the warm-pool maintainer's own sweep, 10 s after startup, cannot
     * destroy it before the held entry is asserted; the destroy assertions hold whichever sweep gets there.
     */
    @Test
    void confirmingHandsTheVmToTheHeldSweep(JenkinsRule r) throws Exception {
        lockDown(r);
        FakeHypervisorClient fake = fakeFinding(NODE).readAs(UUID, VmState.HALTED);
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngLeakedVmStore.get().record(CLOUD, entry());

        confirm(r, r.createWebClient().login("alice"), UUID);
        cloud.sweepLeakedVms();

        assertTrue(
                fake.calls().contains("adoptXapiEraVm:" + UUID + "->" + CLOUD),
                fake.calls().toString());
        List<XcpngLeakedVm> now = List.copyOf(XcpngLeakedVmStore.get().entries(CLOUD));
        assertEquals(1, now.size(), now.toString());
        XcpngLeakedVm held = now.get(0);
        assertEquals(UUID, held.getVmRef());
        assertEquals(XcpngBackend.XO, held.getBackend());
        assertTrue(held.isHeld(), held.toString());
        assertEquals(NODE, held.getNodeName());
        assertTrue(fake.destroyOwners().isEmpty(), "halted, so held: " + fake.calls());
        assertTrue(monitor(r).getLeaked().isEmpty(), "no longer the removed-backend banner's");

        fake.readAs(UUID, VmState.RUNNING);
        cloud.sweepLeakedVms();

        assertTrue(
                fake.calls().contains("destroyWithDisks:" + UUID), fake.calls().toString());
        assertEquals(List.of(CLOUD), fake.destroyOwners());
        assertEquals(Set.of(), XcpngLeakedVmStore.get().refs(CLOUD));
    }

    /** A confirmation for a VM other than the one previewed does nothing, whatever the checks now say. */
    @Test
    void confirmingAnotherUuidThanThePreviewedOneChangesNothing(JenkinsRule r) throws Exception {
        lockDown(r);
        FakeHypervisorClient fake = fakeFinding(NODE);
        cloudBackedBy(r, fake);
        XcpngLeakedVmStore.get().record(CLOUD, entry());

        confirm(r, r.createWebClient().login("alice"), "b81c6d2f-40a7-4e93-8f15-6ad3c0e72b94");

        assertFalse(adopted(fake), fake.calls().toString());
        assertEquals(Set.of(REF), XcpngLeakedVmStore.get().refs(CLOUD));
    }

    /** A failed check is shown on the preview, and a confirmation sent anyway does nothing. */
    @Test
    void aVmThatFailsACheckIsShownAndNotReclaimed(JenkinsRule r) throws Exception {
        lockDown(r);
        FakeHypervisorClient fake = fakeFinding(NODE + "-renamed");
        cloudBackedBy(r, fake);
        XcpngLeakedVmStore.get().record(CLOUD, entry());
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");

        String page = text(preview(r, alice));
        assertTrue(page.contains("xcpng-refusal"), page);
        assertFalse(page.contains("reclaimConfirm"), page);

        confirm(r, alice, UUID);

        assertFalse(adopted(fake), fake.calls().toString());
        assertEquals(Set.of(REF), XcpngLeakedVmStore.get().refs(CLOUD));
    }

    /** XO has no VM with the ref: the entry stays for the administrator to Forget, never dropped on a 404. */
    @Test
    void aRefXoDoesNotKnowKeepsTheEntry(JenkinsRule r) throws Exception {
        lockDown(r);
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        cloudBackedBy(r, fake);
        XcpngLeakedVmStore.get().record(CLOUD, entry());
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");

        String page = text(preview(r, alice));
        confirm(r, alice, UUID);

        assertTrue(page.contains("xcpng-refusal"), page);
        assertFalse(adopted(fake), fake.calls().toString());
        assertEquals(Set.of(REF), XcpngLeakedVmStore.get().refs(CLOUD));
    }

    /** The backend refusing to take the VM over leaves the entry as it was and says why. */
    @Test
    void anAdoptionTheBackendRefusesKeepsTheEntry(JenkinsRule r) throws Exception {
        lockDown(r);
        FakeHypervisorClient fake = fakeFinding(NODE).refuseAdoptionAsNotOwned();
        cloudBackedBy(r, fake);
        XcpngLeakedVmStore.get().record(CLOUD, entry());

        String page = text(confirm(r, r.createWebClient().login("alice"), UUID));

        assertTrue(adopted(fake), fake.calls().toString());
        assertTrue(page.contains("another cloud"), page);
        assertEquals(Set.of(REF), XcpngLeakedVmStore.get().refs(CLOUD));
    }

    /** A cloud still configured for XAPI has no Xen Orchestra connection to look through: refused before any read. */
    @Test
    void aCloudStillOnXapiIsNotLookedThrough(JenkinsRule r) throws Exception {
        lockDown(r);
        FakeHypervisorClient fake = fakeFinding(NODE);
        cloudBackedBy(r, fake).setBackend(XcpngBackend.XAPI);
        XcpngLeakedVmStore.get().record(CLOUD, entry());
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");

        String page = text(preview(r, alice));
        confirm(r, alice, UUID);

        assertTrue(page.contains("xcpng-refusal"), page);
        assertTrue(fake.calls().isEmpty(), fake.calls().toString());
        assertEquals(Set.of(REF), XcpngLeakedVmStore.get().refs(CLOUD));
    }

    /** Neither endpoint is reachable without Administer; not even the read reaches the pool. */
    @Test
    void bothEndpointsNeedAdminister(JenkinsRule r) throws Exception {
        lockDown(r);
        FakeHypervisorClient fake = fakeFinding(NODE);
        cloudBackedBy(r, fake);
        XcpngLeakedVmStore.get().record(CLOUD, entry());
        JenkinsRule.WebClient bob = r.createWebClient().login("bob");
        bob.setThrowExceptionOnFailingStatusCode(false);

        assertEquals(403, preview(r, bob).getWebResponse().getStatusCode());
        assertEquals(403, confirm(r, bob, UUID).getWebResponse().getStatusCode());

        assertTrue(fake.calls().isEmpty(), fake.calls().toString());
        assertEquals(Set.of(REF), XcpngLeakedVmStore.get().refs(CLOUD));
    }
}
