package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.FormValidation;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class XcpngCloudTest {

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
                true,
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
        assertTrue(reloaded.isTrustSelfSigned());
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
                true,
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
     * pool URL, the credential ID and the TLS-trust flag — which is what lets teardown destroy the VM after
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
        XcpngCloud cloud =
                new XcpngCloud("xcpng", "https://pool.example.test", "xcpng-root", true, 2, List.of(template));
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
        assertTrue(reloaded.isTrustSelfSigned(), "the TLS-trust flag must survive the round trip");
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
        XcpngCloud cloud = new XcpngCloud("xcpng", "  https://pool.example.test  ", "id", false, 2, List.of());
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
        FormValidation v = d.doTestConnection("192.168.1.87", "", false);
        assertEquals(FormValidation.Kind.ERROR, v.kind);
        assertTrue(v.getMessage().contains("http"), v.getMessage());
    }

    /**
     * A successful connection made with TLS verification off is reported as a warning, not a clean OK, so
     * the operator is told on the form that the link carrying the pool credential was unverified. A
     * verified connection stays a plain OK. This is the message layer only; it needs no live pool.
     */
    @Test
    void testConnectionSuccessWarnsWhenTlsVerificationDisabled(JenkinsRule r) {
        FormValidation verified = XcpngCloud.DescriptorImpl.connectedResult(false);
        assertEquals(FormValidation.Kind.OK, verified.kind);

        FormValidation unverified = XcpngCloud.DescriptorImpl.connectedResult(true);
        assertEquals(FormValidation.Kind.WARNING, unverified.kind);
        assertTrue(unverified.getMessage().contains("verification"), unverified.getMessage());
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
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", false, 1, List.of()),
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
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", false, 1, List.of()),
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
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "cred", false, 2, List.of());

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
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "xcpng-root", false, 2, List.of());
        assertEquals(10, cloud.getIdleMinutes(), "an unset idle timeout must default to 10, not 0");
    }

    /**
     * A non-positive idle timeout must clamp to the default so the setter can never disable the reap.
     */
    @Test
    void idleMinutesClampsNonPositiveToDefault(JenkinsRule r) {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "xcpng-root", false, 2, List.of());
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
}
