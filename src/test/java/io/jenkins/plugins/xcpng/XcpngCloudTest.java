package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.FormValidation;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class XcpngCloudTest {

    /**
     * A syntactically valid pin, used where a test needs the fingerprint to be present rather than
     * to match anything: against a null the assertions that it reached a snapshot would pass just as
     * happily on a hardcoded default.
     */
    private static final String PINNED_FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    @Test
    void descriptorIsRegistered(JenkinsRule r) {
        assertNotNull(r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class));
    }

    /** Saving the global config and reading it back must preserve every field. */
    @Test
    void configRoundTrip(JenkinsRule r) throws Exception {
        XcpngCloud cloud = new XcpngCloud(
                "xcpng",
                "https://pool.example.test",
                "xcpng-root",
                PINNED_FINGERPRINT,
                3,
                List.of(new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 4, 8192)));
        // A non-default idle timeout so the assertion proves the optional setter survives the form, not
        // merely that the field initializer happens to match.
        cloud.setIdleMinutes(20);
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        XcpngCloud reloaded = (XcpngCloud) r.jenkins.clouds.getByName("xcpng");
        assertNotNull(reloaded);
        assertEquals("https://pool.example.test", reloaded.getPoolUrl());
        assertEquals("xcpng-root", reloaded.getCredentialsId());
        assertEquals(PINNED_FINGERPRINT, reloaded.getCertificateFingerprint());
        assertEquals(3, reloaded.getMaxInstances());
        assertEquals(20, reloaded.getIdleMinutes());
        assertEquals(1, reloaded.getTemplates().size());

        XcpngTemplate template = reloaded.getTemplates().get(0);
        assertEquals("jenkins-golden-debian", template.getTemplateName());
        assertEquals("xcpng-linux", template.getLabelString());
        assertEquals(4, template.getNumCpus());
        assertEquals(8192, template.getMemoryMb());
        assertEquals(8192L * 1024 * 1024, template.getMemoryBytes());
    }

    /**
     * The design stores a credential ID, never the secret. Referencing a real credential by ID and
     * persisting the cloud must leave the plaintext password out of Jenkins' global config.xml.
     */
    @Test
    void credentialSecretIsNotPersisted(JenkinsRule r) throws Exception {
        String secret = "sup3r-s3cret-pw";
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "xcpng-root", "XCP-ng lab", "root", secret));
        SystemCredentialsProvider.getInstance().save();

        r.jenkins.clouds.add(new XcpngCloud(
                "xcpng",
                "https://pool.example.test",
                "xcpng-root",
                PINNED_FINGERPRINT,
                2,
                List.of(new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048))));
        r.jenkins.save();

        Path configXml = r.jenkins.getRootDir().toPath().resolve("config.xml");
        String xml = Files.readString(configXml);
        assertTrue(xml.contains("xcpng-root"), "the credential ID should be stored");
        assertFalse(xml.contains(secret), "the plaintext password must never reach the cloud config");
    }

    /**
     * The agent's connection snapshot follows the same rule one level down: a provisioned node persists the
     * pool URL, the credential ID and the pinned certificate fingerprint — which is what lets teardown
     * destroy the VM after
     * the cloud is deleted or renamed — and never the password behind that ID.
     */
    @Test
    void agentSnapshotIsPersistedButTheSecretIsNot(JenkinsRule r) throws Exception {
        String secret = "sup3r-s3cret-pw";
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "xcpng-root", "XCP-ng lab", "root", secret));
        SystemCredentialsProvider.getInstance().save();

        XcpngTemplate template = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);
        XcpngCloud cloud = new XcpngCloud(
                "xcpng", "https://pool.example.test", "xcpng-root", PINNED_FINGERPRINT, 2, List.of(template));
        cloud.setClientFactory(c -> new FakeHypervisorClient("jenkins-golden-debian"));
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);

        XcpngAgent agent = (XcpngAgent) cloud.provisionNode(
                template,
                "xcpng-agent-1",
                new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", "xcpng-agent-1"),
                false);
        r.jenkins.addNode(agent);

        Path nodeXml = r.jenkins.getRootDir().toPath().resolve(Path.of("nodes", "xcpng-agent-1", "config.xml"));
        String xml = Files.readString(nodeXml);
        assertTrue(xml.contains("https://pool.example.test"), "the pool URL should be stored: " + xml);
        assertTrue(xml.contains("xcpng-root"), "the credential ID should be stored: " + xml);
        assertFalse(xml.contains(secret), "the plaintext password must never reach the node config");

        // Read back through XStream, the way the controller reloads a node on restart: a snapshot that
        // persisted but did not survive deserialization would leave teardown with nothing to connect with.
        XcpngAgent reloaded = (XcpngAgent) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
        assertEquals("https://pool.example.test", reloaded.getPoolUrl());
        assertEquals("xcpng-root", reloaded.getCredentialsId());
        assertEquals(
                PINNED_FINGERPRINT,
                reloaded.getCertificateFingerprint(),
                "the pinned certificate fingerprint must survive the round trip");
    }

    /**
     * The pool URL check accepts an http/https address with a host and rejects everything else, so a
     * schemeless string that {@code new URI(...)} would parse without complaint is still flagged.
     */
    @Test
    void poolUrlValidation(JenkinsRule r) {
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolUrl("https://192.168.1.87").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolUrl("https://pool.example.test:8443").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolUrl("").kind);
        // Plain http would send the XAPI credential in cleartext, so it is rejected outright.
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("http://192.168.1.87").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("http://pool.example.test:443").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("192.168.1.87").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("ftp://pool").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("https://").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("http://[bad").kind);
        // Credentials belong in the Credentials field, never embedded in the URL.
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("https://root:pw@192.168.1.87").kind);
    }

    /** Surrounding whitespace is trimmed on the way in so the persisted value matches what parses. */
    @Test
    void poolUrlIsTrimmedOnConstruction(JenkinsRule r) {
        XcpngCloud cloud = new XcpngCloud("xcpng", "  https://pool.example.test  ", "id", null, 2, List.of());
        assertEquals("https://pool.example.test", cloud.getPoolUrl());
    }

    /**
     * The connection test applies the same scheme/host check as the field validator, so a malformed
     * URL is rejected with that message up front rather than failing deeper in the client. A schemeless
     * value must not fall through to the credentials error.
     */
    @Test
    void testConnectionRejectsMalformedUrlBeforeConnecting(JenkinsRule r) {
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);
        FormValidation v = d.doTestConnection("192.168.1.87", "", null);
        assertEquals(FormValidation.Kind.ERROR, v.kind);
        assertTrue(v.getMessage().contains("http"), v.getMessage());
    }

    /**
     * Both ways of succeeding are secure, so both report a plain OK: the certificate either chained to a
     * CA the JVM trusts or matched the pinned fingerprint. The messages still have to differ, because the
     * operator needs to know which of the two happened -- a pool that silently stopped being pinned and
     * started being CA-trusted is worth noticing. This is the message layer only; it needs no live pool.
     */
    @Test
    void testConnectionReportsWhichWayTheCertificateWasAccepted(JenkinsRule r) {
        FormValidation caTrusted = XcpngCloud.DescriptorImpl.connectedResult(null);
        assertEquals(FormValidation.Kind.OK, caTrusted.kind);

        FormValidation pinned = XcpngCloud.DescriptorImpl.connectedResult(PINNED_FINGERPRINT);
        assertEquals(FormValidation.Kind.OK, pinned.kind);
        assertTrue(pinned.getMessage().contains("pinned"), pinned.getMessage());
        assertNotEquals(
                caTrusted.getMessage(), pinned.getMessage(), "the two ways of succeeding must not read identically");
    }

    /**
     * A cloud saved with the removed "Trust self-signed certificate" option has no honourable
     * interpretation left: that switch accepted any certificate from any host, and no code path does that
     * any more. It must be reported by name at load, so the operator finds out from a startup warning
     * rather than from a build that will not schedule.
     *
     * <p>This test is also the evidence for a claim the migration rests on and that is easy to get wrong:
     * that XStream really does populate the retired field from an old document. It is asserted through the
     * warning, which cannot fire unless the field was read back as true.
     */
    @Test
    void aCloudSavedWithTrustSelfSignedIsRefusedByName(JenkinsRule r) throws Exception {
        String xml = "<io.jenkins.plugins.xcpng.XcpngCloud>\n"
                + "  <name>lab</name>\n"
                + "  <poolUrl>https://pool.example.test</poolUrl>\n"
                + "  <trustSelfSigned>true</trustSelfSigned>\n"
                + "</io.jenkins.plugins.xcpng.XcpngCloud>\n";

        List<LogRecord> log = whileCapturingCloudLog(() -> {
            XcpngCloud cloud = (XcpngCloud) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
            assertNull(
                    cloud.getCertificateFingerprint(),
                    "the retired option must not be silently converted into a pin it never carried");
        });

        assertTrue(
                log.stream()
                        .anyMatch(record -> record.getLevel() == Level.WARNING
                                && messageOf(record).contains("lab")
                                && messageOf(record).contains("Trust self-signed certificate")),
                "the refusal must name the cloud and the removed option: "
                        + log.stream().map(LogRecord::getMessage).toList());
    }

    /**
     * The other half of the migration, and the one that proves the warning discriminates: a cloud that
     * carries the retired flag <em>and</em> a fingerprint has already been migrated by an administrator,
     * so it must load quietly and keep its pin.
     */
    @Test
    void aMigratedCloudKeepsItsPinAndSaysNothing(JenkinsRule r) throws Exception {
        String xml = "<io.jenkins.plugins.xcpng.XcpngCloud>\n"
                + "  <name>lab</name>\n"
                + "  <poolUrl>https://pool.example.test</poolUrl>\n"
                + "  <trustSelfSigned>true</trustSelfSigned>\n"
                + "  <certificateFingerprint>" + PINNED_FINGERPRINT + "</certificateFingerprint>\n"
                + "</io.jenkins.plugins.xcpng.XcpngCloud>\n";

        List<LogRecord> log = whileCapturingCloudLog(() -> {
            XcpngCloud cloud = (XcpngCloud) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
            assertEquals(PINNED_FINGERPRINT, cloud.getCertificateFingerprint());
        });

        assertTrue(
                log.stream().noneMatch(record -> messageOf(record).contains("Trust self-signed certificate")),
                "a migrated cloud must not be warned about: "
                        + log.stream().map(LogRecord::getMessage).toList());
    }

    /**
     * A record's message, never null. {@code LogRecord#getMessage} is nullable, and the handler below keeps
     * every record the logger emits rather than only ours, so one record with no message would turn a
     * predicate into an NPE and a real assertion into a spurious failure.
     */
    private static String messageOf(LogRecord record) {
        String message = record.getMessage();
        return message == null ? "" : message;
    }

    /** Collect everything {@link XcpngCloud} logs while {@code body} runs. */
    private static List<LogRecord> whileCapturingCloudLog(ThrowingRunnable body) throws Exception {
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger logger = Logger.getLogger(XcpngCloud.class.getName());
        logger.addHandler(handler);
        try {
            body.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    /** A body that may throw, so a capture helper can wrap ordinary test code. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * XStream loads global config without the constructor, so an older config.xml predating the
     * templates/maxInstances fields must still reload without an NPE, with the guards re-applied.
     */
    @Test
    void legacyConfigWithoutTemplatesSurvivesReload(JenkinsRule r) {
        String xml = "<io.jenkins.plugins.xcpng.XcpngCloud>\n"
                + "  <name>xcpng</name>\n"
                + "  <poolUrl>https://pool.example.test</poolUrl>\n"
                + "</io.jenkins.plugins.xcpng.XcpngCloud>\n";
        XcpngCloud cloud = (XcpngCloud) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
        assertNotNull(cloud.getTemplates(), "a missing <templates> must not leave the list null");
        assertTrue(cloud.getTemplates().isEmpty());
        assertEquals(1, cloud.getMaxInstances(), "a missing maxInstances must clamp to 1, not 0");
    }

    /**
     * A template persisted before the sizing fields existed reloads with {@code numCpus}/{@code
     * memoryMb} at 0. {@code readResolve} must clamp them to the defaults so provisioning never builds
     * an invalid {@link io.jenkins.plugins.xcpng.client.ProvisionSpec}.
     */
    @Test
    void legacyTemplateWithoutSizingClampsToDefaults(JenkinsRule r) {
        String xml = "<io.jenkins.plugins.xcpng.XcpngTemplate>\n"
                + "  <templateName>jenkins-golden-debian</templateName>\n"
                + "  <labelString>xcpng-linux</labelString>\n"
                + "</io.jenkins.plugins.xcpng.XcpngTemplate>\n";
        XcpngTemplate t = (XcpngTemplate) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
        assertEquals(2, t.getNumCpus(), "missing vCPUs must clamp to the default, not 0");
        assertEquals(2048, t.getMemoryMb(), "missing memory must clamp to the default, not 0");
    }

    /**
     * A template persisted while executors were still configurable carries a {@code <numExecutors>} element
     * no field matches any more. That config must still load rather than break the controller's startup, and
     * the value must be inert: a persisted 2 used to mean two builds shared one VM, which is the arrangement
     * that let the first to finish destroy the VM under the second.
     */
    @Test
    void aLegacyExecutorCountIsIgnoredRatherThanHonoured(JenkinsRule r) throws Exception {
        String xml = "<io.jenkins.plugins.xcpng.XcpngTemplate>\n"
                + "  <templateName>jenkins-golden-debian</templateName>\n"
                + "  <labelString>xcpng-linux</labelString>\n"
                + "  <numExecutors>2</numExecutors>\n"
                + "  <numCpus>4</numCpus>\n"
                + "  <memoryMb>8192</memoryMb>\n"
                + "</io.jenkins.plugins.xcpng.XcpngTemplate>\n";

        XcpngTemplate t = (XcpngTemplate) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);

        // The unknown element is dropped, and the fields beside it still load: proof the legacy config is
        // absorbed rather than rejected.
        assertEquals("jenkins-golden-debian", t.getTemplateName());
        assertEquals(4, t.getNumCpus(), "the fields around the dropped element must still load");
        assertEquals(8192, t.getMemoryMb());

        // What the operator actually gets: one executor, whatever the old config asked for.
        XcpngAgent agent = new XcpngAgent(
                "xcpng-legacy-1",
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", null, 1, List.of()),
                "vm/legacy/1",
                t,
                10,
                new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", "xcpng-legacy-1"),
                false);
        assertEquals(1, agent.getNumExecutors(), "a legacy numExecutors=2 must not resurrect a shared VM");
    }

    /**
     * A provisioned agent is {@code EXCLUSIVE}, so only builds whose label expression matches the
     * template's labels are scheduled onto it. {@code NORMAL} — "use this node as much as possible" —
     * would let any unlabeled build take a single-use VM, including a warm spare held for a label, and
     * destroy it on the way out.
     */
    @Test
    void aProvisionedAgentOnlyServesMatchingLabels(JenkinsRule r) throws Exception {
        XcpngAgent agent = new XcpngAgent(
                "xcpng-agent-1",
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", null, 1, List.of()),
                "vm/xcpng-agent-1/1",
                new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048),
                10,
                new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", "xcpng-agent-1"),
                false);

        assertEquals(
                hudson.model.Node.Mode.EXCLUSIVE,
                agent.getMode(),
                "NORMAL would make every unlabeled build eligible for a single-use VM");
    }

    /**
     * An agent whose node {@code config.xml} was written before the mode changed carries
     * {@code <mode>NORMAL</mode>}, and {@code Slave.mode} is a persisted field rather than a transient
     * one, so XStream restores it and the constructor never runs. Without {@code readResolve} asserting
     * the mode, such an agent comes back from a restart still able to take unlabeled builds: the very
     * bug the mode exists to prevent, outliving the upgrade that fixed it.
     */
    @Test
    void aLegacyAgentPersistedAsNormalReloadsAsExclusive(JenkinsRule r) {
        String xml = "<io.jenkins.plugins.xcpng.XcpngAgent>\n"
                + "  <name>xcpng-legacy-mode-1</name>\n"
                + "  <description>XCP-ng ephemeral agent</description>\n"
                + "  <remoteFS>/home/debian/agent</remoteFS>\n"
                + "  <numExecutors>1</numExecutors>\n"
                + "  <mode>NORMAL</mode>\n"
                + "  <label>xcpng-linux</label>\n"
                + "  <cloudName>xcpng</cloudName>\n"
                + "  <vmRef>vm/legacy/1</vmRef>\n"
                + "</io.jenkins.plugins.xcpng.XcpngAgent>\n";

        XcpngAgent agent = (XcpngAgent) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);

        assertEquals(
                hudson.model.Node.Mode.EXCLUSIVE,
                agent.getMode(),
                "a persisted NORMAL must not survive a reload and keep taking unlabeled builds");
    }

    /**
     * {@code minInstances} is an optional warm-pool setter. A template persisted before it existed
     * reloads with the field at 0, which is exactly the "warm pool off" default, so no warm agents are
     * ever booted for a legacy config.
     */
    @Test
    void legacyTemplateWithoutMinInstancesDefaultsToZero(JenkinsRule r) {
        String xml = "<io.jenkins.plugins.xcpng.XcpngTemplate>\n"
                + "  <templateName>jenkins-golden-debian</templateName>\n"
                + "  <labelString>xcpng-linux</labelString>\n"
                + "</io.jenkins.plugins.xcpng.XcpngTemplate>\n";
        XcpngTemplate t = (XcpngTemplate) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
        assertEquals(0, t.getMinInstances(), "a missing minInstances must default to 0 (warm pool off)");
    }

    /**
     * The warm-pool size floors at 0 (its valid "off" value), not at a positive default: a negative
     * value cannot mean "negative agents", and 0 must survive as-is.
     */
    @Test
    void minInstancesClampsNegativeToZero(JenkinsRule r) {
        XcpngTemplate t = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);
        assertEquals(0, t.getMinInstances(), "an unset warm-pool size must be 0");
        t.setMinInstances(-3);
        assertEquals(0, t.getMinInstances(), "a negative warm-pool size must clamp to 0");
        t.setMinInstances(2);
        assertEquals(2, t.getMinInstances(), "a positive warm-pool size must be kept as-is");
    }

    /**
     * Labels are what a build uses to reach these agents, and the only thing: the nodes are
     * {@code EXCLUSIVE} and the cloud declines a null label, so a template with no labels provisions
     * nothing. Reject it at the form rather than accept a config that looks complete and never runs.
     */
    @Test
    void aTemplateWithoutLabelsIsRejected(JenkinsRule r) {
        XcpngTemplate.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngTemplate.DescriptorImpl.class);

        assertEquals(FormValidation.Kind.ERROR, d.doCheckLabelString(null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckLabelString("").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckLabelString("   ").kind, "whitespace is not a label");
        assertEquals(FormValidation.Kind.OK, d.doCheckLabelString("xcpng-linux").kind);
    }

    /**
     * The warm-pool field validator: rejects negatives, and warns (does not block) when the target
     * exceeds the enclosing cloud's instance cap, since warm agents count against that cap. A null
     * cloud (field checked outside a cloud form) skips the cross-field warning.
     */
    @Test
    void minInstancesValidationWarnsWhenAboveCap(JenkinsRule r) {
        XcpngTemplate.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngTemplate.DescriptorImpl.class);
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "cred", null, 2, List.of());

        assertEquals(FormValidation.Kind.OK, d.doCheckMinInstances(cloud, "").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckMinInstances(cloud, "2").kind, "at the cap is fine");
        assertEquals(FormValidation.Kind.ERROR, d.doCheckMinInstances(cloud, "-1").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckMinInstances(cloud, "x").kind);
        assertEquals(
                FormValidation.Kind.WARNING,
                d.doCheckMinInstances(cloud, "5").kind,
                "a target above the cap must warn, not block");
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckMinInstances(null, "5").kind,
                "no enclosing cloud means no cross-field warning");
    }

    /**
     * {@code idleMinutes} is an optional setter, so a cloud built without it must carry the default
     * timeout rather than 0, which would switch the idle safety net off entirely.
     */
    @Test
    void idleMinutesDefaultsWhenUnset(JenkinsRule r) {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "xcpng-root", null, 2, List.of());
        assertEquals(10, cloud.getIdleMinutes(), "an unset idle timeout must default to 10, not 0");
    }

    /**
     * A non-positive idle timeout must clamp to the default so the setter can never disable the reap.
     */
    @Test
    void idleMinutesClampsNonPositiveToDefault(JenkinsRule r) {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "xcpng-root", null, 2, List.of());
        cloud.setIdleMinutes(0);
        assertEquals(10, cloud.getIdleMinutes(), "zero must clamp to the default, not disable the reap");
        cloud.setIdleMinutes(-5);
        assertEquals(10, cloud.getIdleMinutes(), "a negative value must clamp to the default");
        cloud.setIdleMinutes(15);
        assertEquals(15, cloud.getIdleMinutes(), "a positive value must be kept as-is");
    }

    /**
     * A config persisted before {@code idleMinutes} existed reloads with the field at 0. {@code
     * readResolve} must restore the default so the safety-net reap is never left switched off.
     */
    @Test
    void legacyConfigWithoutIdleMinutesRestoresDefault(JenkinsRule r) {
        String xml = "<io.jenkins.plugins.xcpng.XcpngCloud>\n"
                + "  <name>xcpng</name>\n"
                + "  <poolUrl>https://pool.example.test</poolUrl>\n"
                + "  <maxInstances>2</maxInstances>\n"
                + "</io.jenkins.plugins.xcpng.XcpngCloud>\n";
        XcpngCloud cloud = (XcpngCloud) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
        assertEquals(10, cloud.getIdleMinutes(), "a missing idleMinutes must restore the default, not stay 0");
    }

    /**
     * Saving the cloud from its own configuration page must not forget the VMs a teardown failed to destroy.
     *
     * <p>This is the half of #149 the issue did not name, and the likelier one in practice: the issue is
     * written about configuration-as-code, but {@code /manage/cloud/<name>/configure} rebinds the cloud
     * through the same {@code @DataBoundConstructor} and hands back a different object. Measured on
     * 2026-08-26 before the fix -- same instance {@code false}, leaked set {@code []} -- so an operator
     * raising {@code maxInstances} silently dropped every reference the plugin was still holding, and the
     * only thing left pointing at those VMs was the external reaper.
     */
    @Test
    void aUiSaveOfTheCloudKeepsTheLeakedVmSet(JenkinsRule r) throws Exception {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "xcpng-root", null, 2, List.of());
        r.jenkins.clouds.add(cloud);
        cloud.recordLeakedVm("OpaqueRef:leaked-1");

        r.submit(r.createWebClient().goTo("manage/cloud/xcpng/configure").getFormByName("config"));

        XcpngCloud saved = (XcpngCloud) r.jenkins.clouds.getByName("xcpng");
        assertNotNull(saved, "the save must leave a cloud behind");
        assertNotEquals(
                System.identityHashCode(cloud),
                System.identityHashCode(saved),
                "a UI save is expected to rebuild the cloud; if it stopped doing so this test proves nothing");
        assertTrue(
                saved.leakedVmRefs().contains("OpaqueRef:leaked-1"),
                "a UI save must not forget a VM the plugin failed to destroy: " + saved.leakedVmRefs());
    }
}
