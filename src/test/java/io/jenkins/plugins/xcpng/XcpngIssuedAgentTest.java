package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.DumbSlave;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * Only an agent whose definition still matches what the plugin issued it is given a session to the pool or
 * allowed to destroy a VM.
 *
 * <p>Each unissued node here is a real agent's definition read back through {@code Jenkins.XSTREAM2} with one
 * field changed, and each test ends with a control: the agent the plugin did issue still reaches the fake, so
 * a quiet fake means the check held rather than that nothing ran.
 */
@WithJenkins
class XcpngIssuedAgentTest {

    private static final XcpngTemplate TEMPLATE = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

    private static XcpngCloud cloudBackedBy(FakeHypervisorClient fake) {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "cred", null, 2, List.of(TEMPLATE));
        cloud.setClientFactory(c -> fake);
        cloud.setWaitForOnline(false);
        return cloud;
    }

    private static XcpngAgent provisioned(JenkinsRule r, XcpngCloud cloud, String name) throws Exception {
        XcpngAgent agent = cloud.createAgent(
                TEMPLATE, name, new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", name), false);
        r.jenkins.addNode(agent);
        awaitTrue(
                () -> agent.getVmRef() != null && !(agent.toComputer() instanceof SlaveComputer c && c.isConnecting()),
                "the launcher never provisioned " + name);
        return agent;
    }

    /** A node read back from a definition, the way core loads one. */
    private static XcpngAgent fromDefinition(String xml) {
        return (XcpngAgent) Jenkins.XSTREAM2.fromXML(xml);
    }

    private static long destroys(FakeHypervisorClient fake) {
        return fake.calls().stream()
                .filter(c -> c.startsWith("destroyWithDisks:"))
                .count();
    }

    @Test
    void anUnissuedNodeNamingAnIssuedVmDoesNotDestroyIt(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake);
        r.jenkins.clouds.add(cloud);
        XcpngAgent original = provisioned(r, cloud, "xcpng-agent-1");

        // The original's definition under a new name, VM handle and all.
        XcpngAgent second = fromDefinition(Jenkins.XSTREAM2
                .toXML(original)
                .replace("<name>xcpng-agent-1</name>", "<name>xcpng-agent-second</name>"));
        assertEquals(original.getVmRef(), second.getVmRef(), "the unissued node names the same VM");

        assertFalse(second.isIssued());
        second.terminate();
        assertEquals(0, destroys(fake), "its teardown must not reach the pool: " + fake.calls());
        assertTrue(XcpngLeakedVmStore.get().entries("xcpng").isEmpty(), "nor record a leak to retry it");

        // Control: the agent the plugin issued still destroys its own VM through the same fake.
        original.terminate();
        assertEquals(
                List.of("destroyWithDisks:" + original.getVmRef()),
                fake.calls().stream()
                        .filter(c -> c.startsWith("destroyWithDisks:"))
                        .toList());
        assertEquals(List.of("xcpng"), fake.destroyOwners(), "the destroy names the owning cloud");
    }

    @Test
    void aChangedConnectionIsRefusedBeforeAnySessionIsOpened(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake);
        r.jenkins.clouds.add(cloud);
        XcpngAgent original = provisioned(r, cloud, "xcpng-agent-1");

        XcpngAgent rewritten = fromDefinition(Jenkins.XSTREAM2
                .toXML(original)
                .replace("https://pool.example.test", "https://elsewhere.example.test"));

        assertFalse(rewritten.isIssued());
        // The message matters: without the guard this still throws, because the credential "cred" does not
        // exist, but only after the request was built for the rewritten URL.
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rewritten.openClientForVm(cloud));
        assertTrue(e.getMessage().contains("did not issue"), e.getMessage());

        rewritten.terminate();
        assertTrue(
                XcpngLeakedVmStore.get().entries("xcpng").isEmpty(),
                "a refused teardown must not hand the rewritten connection to the leaked-VM sweep");

        // Control, on a node the guard has not seen: the plugin's own agent is still issued and opens.
        XcpngAgent other = provisioned(r, cloud, "xcpng-agent-2");
        assertTrue(other.isIssued());
        assertDoesNotThrow(() -> other.openClientForVm(cloud).close());
    }

    @Test
    void aNodeThePluginNeverIssuedIsNotLaunched(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake);
        r.jenkins.clouds.add(cloud);
        XcpngAgent original = provisioned(r, cloud, "xcpng-agent-1");
        int clonesBefore = clones(fake);

        // A fresh name, no VM yet, and a different connection.
        String xml = Jenkins.XSTREAM2
                .toXML(original)
                .replace("<name>xcpng-agent-1</name>", "<name>xcpng-unissued</name>")
                .replace("https://pool.example.test", "https://elsewhere.example.test")
                .replaceAll("<vmRef>[^<]*</vmRef>", "");
        XcpngAgent created = fromDefinition(xml);
        r.jenkins.addNode(created);
        SlaveComputer computer = (SlaveComputer) created.toComputer();
        awaitTrue(() -> !computer.isConnecting(), "the launcher never finished with the unissued node");

        // The refusal itself, not just a quiet fake: without the guard this node still clones nothing here,
        // because its connection has no fake behind it, and fails later at the credential lookup instead.
        assertTrue(computer.getLog().contains("was not provisioned by XCP-ng cloud"), computer.getLog());
        assertEquals(clonesBefore, clones(fake), "a node the plugin did not issue must not be cloned for");
    }

    private static int clones(FakeHypervisorClient fake) {
        return (int) fake.calls().stream()
                .filter(c -> c.startsWith("cloneFromTemplate:"))
                .count();
    }

    @Test
    void replacingTheNodeFromXmlNeedsAdminister(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake);
        r.jenkins.clouds.add(cloud);
        XcpngAgent agent = provisioned(r, cloud, "xcpng-agent-1");
        DumbSlave plain = r.createSlave("plain", null, null);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, Computer.CONFIGURE, Computer.EXTENDED_READ)
                .everywhere()
                .to("operator"));
        String agentXml = Jenkins.XSTREAM2.toXML(agent);
        String plainXml = Jenkins.XSTREAM2.toXML(plain);

        try (ACLContext ignored = ACL.as2(User.getById("operator", true).impersonate2())) {
            // Control: the permission set really does allow this on an ordinary node.
            assertDoesNotThrow(() -> plain.toComputer().updateByXml(stream(plainXml)));
            assertThrows(AccessDeniedException.class, () -> agent.toComputer().updateByXml(stream(agentXml)));
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertDoesNotThrow(() -> agent.toComputer().updateByXml(stream(agentXml)));
        }
    }

    private static ByteArrayInputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anEntryWhoseNodeIsGoneIsPrunedAtStartup(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake);
        r.jenkins.clouds.add(cloud);
        XcpngAgent agent = provisioned(r, cloud, "xcpng-agent-1");
        assertTrue(agent.isIssued());

        // What the startup pass sees for a node that did not survive a restart.
        XcpngIssuedAgentStore.get().seedAndPrune(List.of());

        assertFalse(agent.isIssued(), "a pruned name must not stay trusted for a node created under it later");
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message);
    }
}
