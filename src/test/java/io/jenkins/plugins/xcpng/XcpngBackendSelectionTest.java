package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.XoRestClient;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * What the backend field means now that Xen Orchestra is the only backend (#89), and how a cloud carried over
 * from the removed XAPI backend is refused.
 *
 * <p>The decision under test: a cloud whose {@code config.xml} names XAPI, or names no backend because it
 * predates the field, <em>loads</em> with its configuration intact and is then refused by name everywhere a
 * client would be opened. It is not made to fail deserialization, because core drops a cloud that fails to
 * load and the next save of the clouds page erases it.
 *
 * <p>Nothing here opens a socket. {@link XoRestClient} builds its transport in the constructor and connects on
 * the first call, so asserting which client came back costs no network.
 */
@WithJenkins
class XcpngBackendSelectionTest {

    private static final String XO_URL = "https://192.168.1.5";

    private static final String PINNED_FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    /**
     * IDs of the two stored credentials, not the credentials themselves. Named for what they are for rather
     * than for the credential kind: a constant named for a password and holding an ID reads as a hardcoded
     * password to a secret scanner, and GitGuardian flagged exactly that on the first push of this file.
     */
    private static final String LEFTOVER_CREDENTIAL_ID = "xcpng-root";

    private static final String XO_CREDENTIAL_ID = "xo-token";

    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

    /** A username/password credential: what an XAPI-era cloud has selected, and the kind XO cannot use. */
    private static void addPasswordCredential(String id) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, id, "XAPI", "root", "hunter2"));
        SystemCredentialsProvider.getInstance().save();
    }

    /** A secret-text credential, the kind Xen Orchestra authenticates with. */
    private static void addTokenCredential(String id) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, id, "XO token", Secret.fromString("an-xo-authentication-token")));
        SystemCredentialsProvider.getInstance().save();
    }

    private static XcpngCloud cloud(String name, String url, String credentialsId) {
        return new XcpngCloud(name, url, credentialsId, PINNED_FINGERPRINT, 2, List.of(LINUX_TEMPLATE));
    }

    /** The {@code config.xml} of a cloud saved by an older release, with {@code backendElement} spliced in. */
    private static XcpngCloud reloaded(String backendElement) {
        String xml = "<io.jenkins.plugins.xcpng.XcpngCloud>\n"
                + "  <name>old-pool</name>\n"
                + backendElement
                + "  <poolUrl>https://pool.example.test</poolUrl>\n"
                + "  <credentialsId>" + LEFTOVER_CREDENTIAL_ID + "</credentialsId>\n"
                + "  <maxInstances>2</maxInstances>\n"
                + "</io.jenkins.plugins.xcpng.XcpngCloud>\n";
        return (XcpngCloud) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
    }

    // ---- What an absence means, which depends on where it arrives ----

    /**
     * A cloud built through the form or a configuration-as-code document speaks XO without being told to.
     * The form no longer submits a backend at all, so this default is the only way a new cloud gets one.
     */
    @Test
    void aNewlyConfiguredCloudSpeaksXo(JenkinsRule r) {
        XcpngCloud cloud = cloud("new", XO_URL, XO_CREDENTIAL_ID);
        assertEquals(XcpngBackend.XO, cloud.getBackend());
        assertTrue(cloud.isBackendSupported());

        cloud.setBackend(null);
        assertEquals(
                XcpngBackend.XO, cloud.getBackend(), "a document binding an empty backend is asking for the default");
    }

    /**
     * The opposite absence. A {@code config.xml} written before the backend field existed reloads with it
     * null, because XStream runs no constructor, and every such cloud was provisioning over XAPI. Reading it as
     * the new default would point an XAPI pool URL and a root password at the Xen Orchestra client, which fails
     * as an authentication error that says nothing about why. It has to read as what it was, and be written
     * back out by name.
     */
    @Test
    void aConfigPersistedBeforeTheBackendExistedReloadsAsXapi(JenkinsRule r) {
        XcpngCloud cloud = reloaded("");

        assertEquals(XcpngBackend.XAPI, cloud.getBackend(), "a missing backend must read as XAPI, not as the default");
        assertFalse(cloud.isBackendSupported());
        assertTrue(
                jenkins.model.Jenkins.XSTREAM2.toXML(cloud).contains("<backend>XAPI</backend>"),
                "the reloaded cloud must write the backend out by name rather than as an absence");
    }

    /**
     * The control for the test above: a {@code config.xml} that names XO reloads as XO. Without it, a
     * {@code readResolve} that forced every reloaded cloud to XAPI would pass the test above.
     */
    @Test
    void aConfigNamingXoReloadsAsXo(JenkinsRule r) {
        XcpngCloud cloud = reloaded("  <backend>XO</backend>\n");
        assertEquals(XcpngBackend.XO, cloud.getBackend());
        assertTrue(cloud.isBackendSupported());
    }

    /** A cloud that named XAPI explicitly loads too: its configuration survives, and it is refused later. */
    @Test
    void aConfigNamingXapiStillLoads(JenkinsRule r) {
        XcpngCloud cloud = reloaded("  <backend>XAPI</backend>\n");
        assertEquals(XcpngBackend.XAPI, cloud.getBackend());
        assertEquals("https://pool.example.test", cloud.getPoolUrl(), "the configuration must survive the load");
        assertEquals(LEFTOVER_CREDENTIAL_ID, cloud.getCredentialsId());
    }

    // ---- Which client comes back, and what is refused ----

    @Test
    void theXoBackendBuildsAnXoClient(JenkinsRule r) throws Exception {
        addTokenCredential(XO_CREDENTIAL_ID);
        try (HypervisorClient client =
                XcpngCloud.openClient(XO_URL, XO_CREDENTIAL_ID, PINNED_FINGERPRINT, XcpngBackend.XO, "a test")) {
            assertInstanceOf(XoRestClient.class, client);
        }
    }

    /**
     * The refusal, and the reason it lives in {@code openClient}: every path that talks to a hypervisor opens
     * its client there. It must fire even when everything else about the parameters would build a client --
     * a valid URL and a token credential -- or it is a credential check wearing the wrong message.
     */
    @Test
    void theXapiBackendIsRefusedByName(JenkinsRule r) throws Exception {
        addTokenCredential(XO_CREDENTIAL_ID);
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> XcpngCloud.openClient(
                        XO_URL, XO_CREDENTIAL_ID, PINNED_FINGERPRINT, XcpngBackend.XAPI, "cloud 'old-pool'"));
        assertTrue(e.getMessage().contains("cloud 'old-pool'"), e.getMessage());
        assertTrue(e.getMessage().contains("XAPI backend has been removed"), e.getMessage());
        assertTrue(e.getMessage().contains("Xen Orchestra"), "the message must say what to move to: " + e.getMessage());
    }

    /**
     * A null backend is what an agent or a leaked-VM record persisted before the field existed carries, and
     * every one of those was provisioned over XAPI. It must be refused the same way, not built as XO: its ref
     * is an {@code OpaqueRef}, and Xen Orchestra resolves one wherever it takes a VM id (#223), so handing it
     * over would not fail politely.
     */
    @Test
    void aNullBackendFromAnOldSnapshotIsRefusedToo(JenkinsRule r) throws Exception {
        addTokenCredential(XO_CREDENTIAL_ID);
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> XcpngCloud.openClient(XO_URL, XO_CREDENTIAL_ID, null, null, "agent 'old-agent'"));
        assertTrue(e.getMessage().contains("XAPI backend has been removed"), e.getMessage());
    }

    /**
     * The refusal comes before the URL check. An XAPI cloud with no URL is told the one thing that fixes it,
     * rather than being sent to fill in a URL and then meeting the refusal anyway.
     */
    @Test
    void theRefusalComesBeforeTheUrlCheck(JenkinsRule r) {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> XcpngCloud.openClient(null, LEFTOVER_CREDENTIAL_ID, null, XcpngBackend.XAPI, "cloud 'lab'"));
        assertTrue(e.getMessage().contains("XAPI backend has been removed"), e.getMessage());
    }

    /**
     * A username/password credential left selected from an XAPI-era configuration must fail by naming the
     * kind Xen Orchestra needs. A widened lookup that found it and cast it would throw a
     * {@code ClassCastException} from inside the client, which reads as a bug in the plugin.
     */
    @Test
    void aUsernamePasswordCredentialIsRefusedAsTheWrongKind(JenkinsRule r) throws Exception {
        addPasswordCredential(LEFTOVER_CREDENTIAL_ID);
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> XcpngCloud.openClient(XO_URL, LEFTOVER_CREDENTIAL_ID, null, XcpngBackend.XO, "cloud 'xo-lab'"));
        assertTrue(e.getMessage().contains("cloud 'xo-lab'"), e.getMessage());
        assertTrue(e.getMessage().contains("secret-text"), "the message must name the kind needed: " + e.getMessage());
    }

    /**
     * {@code XoRestClient}'s base URL is {@code @NonNull}, so a cloud with no URL has to be answered before
     * the constructor rather than by it (#101). Null and blank arrive by different routes: a JCasC document
     * that omits the key, and a hand-edited {@code config.xml} with an empty element.
     */
    @Test
    void aMissingUrlNamesTheCloudAndTheField(JenkinsRule r) throws Exception {
        addTokenCredential(XO_CREDENTIAL_ID);
        for (String missing : new String[] {null, "", "   "}) {
            IllegalStateException e = assertThrows(
                    IllegalStateException.class,
                    () -> XcpngCloud.openClient(missing, XO_CREDENTIAL_ID, null, XcpngBackend.XO, "cloud 'xo-lab'"),
                    "a poolUrl of " + (missing == null ? "null" : "'" + missing + "'") + " must be refused here");
            assertTrue(e.getMessage().contains("cloud 'xo-lab'"), e.getMessage());
            assertTrue(e.getMessage().contains("Xen Orchestra URL"), e.getMessage());
            // The credential is present and valid, so the credential message instead would mean the URL guard
            // never ran.
            assertFalse(e.getMessage().contains("token credential"), e.getMessage());
        }
    }

    /** A cloud merely missing its credential gets that message, not a URL complaint. */
    @Test
    void aMissingCredentialIsReportedAsThatWhenTheUrlIsFine(JenkinsRule r) {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> XcpngCloud.openClient(XO_URL, "no-such-credential", null, XcpngBackend.XO, "cloud 'lab'"));
        assertTrue(e.getMessage().contains("token credential"), e.getMessage());
        assertFalse(e.getMessage().contains("Xen Orchestra URL"), e.getMessage());
    }

    // ---- An XAPI cloud does nothing ----

    /**
     * The same cloud answers yes as XO and no as XAPI, so the only thing the assertion can be reading is the
     * backend. Asserted on both entry points, because core asks {@code canProvision} and a direct caller can
     * skip it.
     */
    @Test
    void anXapiCloudProvisionsNothing(JenkinsRule r) {
        XcpngCloud cloud = cloud("lab", XO_URL, XO_CREDENTIAL_ID);
        cloud.setClientFactory(c -> new FakeHypervisorClient("jenkins-golden-debian"));
        r.jenkins.clouds.add(cloud);
        Cloud.CloudState linux = new Cloud.CloudState(Label.get("xcpng-linux"), 0);
        assertTrue(cloud.canProvision(linux), "control: the same cloud on XO must be able to provision");

        cloud.setBackend(XcpngBackend.XAPI);

        assertFalse(cloud.canProvision(linux));
        assertTrue(cloud.provision(linux, 1).isEmpty(), "provision must plan nothing even when called anyway");
    }

    /**
     * The warm pool is the one path that provisions without core asking, so it needs its own gate. The
     * executor counts submissions and runs none, which is enough: every warm launch is one submission, and the
     * XO control shows the count is able to move.
     */
    @Test
    void anXapiCloudKeepsNoWarmPool(JenkinsRule r) {
        XcpngTemplate warm = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);
        warm.setMinInstances(1);
        XcpngCloud cloud = new XcpngCloud("lab", XO_URL, XO_CREDENTIAL_ID, null, 2, List.of(warm));
        cloud.setClientFactory(c -> new FakeHypervisorClient("jenkins-golden-debian"));
        r.jenkins.clouds.add(cloud);

        CountingExecutor control = new CountingExecutor();
        cloud.setProvisionExecutor(control);
        cloud.reconcileWarmPool();
        assertEquals(1, control.submitted.get(), "control: an XO cloud with a warm target must launch a spare");

        XcpngCloud xapi = new XcpngCloud("lab-xapi", XO_URL, XO_CREDENTIAL_ID, null, 2, List.of(warm));
        xapi.setBackend(XcpngBackend.XAPI);
        xapi.setClientFactory(c -> new FakeHypervisorClient("jenkins-golden-debian"));
        r.jenkins.clouds.add(xapi);
        CountingExecutor refused = new CountingExecutor();
        xapi.setProvisionExecutor(refused);
        xapi.reconcileWarmPool();
        assertEquals(0, refused.submitted.get(), "an XAPI cloud must not launch a warm spare");
    }

    /** Records each submission and runs nothing, so a test can count launches without any happening. */
    private static final class CountingExecutor extends AbstractExecutorService {
        final AtomicInteger submitted = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            submitted.incrementAndGet();
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }

    // ---- What the form says ----

    @Test
    void theCredentialCheckWarnsOnAnythingButAToken(JenkinsRule r) throws Exception {
        addPasswordCredential(LEFTOVER_CREDENTIAL_ID);
        addTokenCredential(XO_CREDENTIAL_ID);
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);

        assertEquals(FormValidation.Kind.OK, d.doCheckCredentialsId(XO_CREDENTIAL_ID, XO_URL).kind);
        assertEquals(
                FormValidation.Kind.WARNING,
                d.doCheckCredentialsId(LEFTOVER_CREDENTIAL_ID, XO_URL).kind,
                "a username/password credential left over from XAPI must be flagged");
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckCredentialsId("", XO_URL).kind,
                "an empty field is a fresh form, not a mistake to nag about");
    }

    /**
     * Test connection reports a leftover username/password credential as the wrong kind rather than trying
     * it: sent as a token it would come back as an authentication failure, which sends the operator to check
     * a password that is not the problem.
     */
    @Test
    void testConnectionNamesTheCredentialKindItNeeds(JenkinsRule r) throws Exception {
        addPasswordCredential(LEFTOVER_CREDENTIAL_ID);
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);

        FormValidation v = d.doTestConnection(XO_URL, LEFTOVER_CREDENTIAL_ID, null);

        assertEquals(FormValidation.Kind.ERROR, v.kind);
        assertTrue(v.getMessage().contains("secret-text"), "the message must name the kind needed: " + v.getMessage());
    }

    // ---- How an XAPI cloud gets out of that state ----

    /**
     * Saving the configuration page is the migration. The form no longer carries a backend, so the cloud it
     * builds takes the XO default, and the operator's own save is what moves it. Nothing is migrated behind
     * their back: before the save the cloud is still XAPI, and its page says so.
     *
     * <p>Submitted through the cloud's own page, not {@code configRoundtrip()}: clouds left the global
     * configuration page for {@code /manage/cloud/}, so a global round trip never rebinds a cloud at all and
     * a test built on it passes whatever the form does. The previous version of this class had exactly that
     * test.
     */
    @Test
    void savingAnXapiCloudsFormMovesItToXo(JenkinsRule r) throws Exception {
        XcpngCloud old = cloud("old-pool", XO_URL, XO_CREDENTIAL_ID);
        old.setBackend(XcpngBackend.XAPI);
        r.jenkins.clouds.add(old);
        String warning = "still configured for the XAPI backend";

        HtmlPage page = r.createWebClient().goTo("manage/cloud/old-pool/configure");
        assertTrue(page.asNormalizedText().contains(warning), "the page must say why the cloud does nothing");
        r.submit(page.getFormByName("config"));

        XcpngCloud saved = (XcpngCloud) r.jenkins.clouds.getByName("old-pool");
        assertEquals(XcpngBackend.XO, saved.getBackend(), "a save through the form must leave the cloud on XO");
        assertFalse(
                r.createWebClient()
                        .goTo("manage/cloud/old-pool/configure")
                        .asNormalizedText()
                        .contains(warning),
                "control: once on XO, the page must stop showing the warning");
    }

    /** The XO half of the round trip: the only backend there is survives a save. */
    @Test
    void anXoCloudSurvivesAFormSave(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(cloud("xo-lab", XO_URL, XO_CREDENTIAL_ID));

        r.submit(r.createWebClient().goTo("manage/cloud/xo-lab/configure").getFormByName("config"));

        assertEquals(XcpngBackend.XO, ((XcpngCloud) r.jenkins.clouds.getByName("xo-lab")).getBackend());
    }

    // ---- The monitor ----

    /**
     * The monitor names exactly the XAPI clouds. The XO cloud beside it is the control: a monitor that listed
     * every XCP-ng cloud would pass an assertion that only checked the XAPI one was present.
     */
    @Test
    void theMonitorNamesOnlyTheCloudsStillOnXapi(JenkinsRule r) {
        XcpngRemovedBackendMonitor monitor =
                r.jenkins.getExtensionList(XcpngRemovedBackendMonitor.class).get(0);
        r.jenkins.clouds.add(cloud("xo-lab", XO_URL, XO_CREDENTIAL_ID));
        assertFalse(monitor.isActivated(), "an XO cloud alone must not raise the monitor");

        XcpngCloud old = cloud("old-pool", XO_URL, XO_CREDENTIAL_ID);
        old.setBackend(XcpngBackend.XAPI);
        r.jenkins.clouds.add(old);

        assertTrue(monitor.isActivated());
        assertEquals(
                List.of("old-pool"),
                monitor.getClouds().stream().map(c -> c.name).toList());
    }

    /**
     * An agent provisioned over XAPI is named from its own snapshot, not its cloud's: after the cloud has been
     * moved to XO the agent's VM is still unreachable, and that is the case the monitor exists to keep visible.
     *
     * <p>An XO agent is registered beside it. Without that control a monitor listing every XCP-ng agent passed
     * this test, which a mutation removing the backend filter showed.
     */
    @Test
    void theMonitorNamesAnXapiAgentEvenAfterItsCloudMoved(JenkinsRule r) throws Exception {
        XcpngRemovedBackendMonitor monitor =
                r.jenkins.getExtensionList(XcpngRemovedBackendMonitor.class).get(0);
        XcpngCloud cloud = cloud("lab", XO_URL, XO_CREDENTIAL_ID);
        cloud.setClientFactory(c -> new FakeHypervisorClient("jenkins-golden-debian"));
        r.jenkins.clouds.add(cloud);
        cloud.setBackend(XcpngBackend.XAPI);
        XcpngAgent agent = cloud.createAgent(
                LINUX_TEMPLATE,
                "xcpng-old-agent",
                new org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Id(
                        "lab", "jenkins-golden-debian", "xcpng-old-agent"),
                false);
        cloud.setBackend(XcpngBackend.XO);
        XcpngAgent current = cloud.createAgent(
                LINUX_TEMPLATE,
                "xcpng-new-agent",
                new org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Id(
                        "lab", "jenkins-golden-debian", "xcpng-new-agent"),
                false);
        r.jenkins.addNode(current);
        assertFalse(monitor.isActivated(), "control: an agent provisioned over XO must not raise the monitor");

        r.jenkins.addNode(agent);

        assertEquals(List.of("xcpng-old-agent"), monitor.getAgentNames());
        assertTrue(monitor.getClouds().isEmpty(), "the cloud itself is on XO now");
    }
}
