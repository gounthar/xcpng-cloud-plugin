package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.DumbSlave;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.HttpResponses;
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
        assertTrue(original.isIssued(), "tearing down an unissued node must not drop the issued agent's record");

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
    void replacingTheNodeFromXmlIsRefused(JenkinsRule r) throws Exception {
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
            assertThrows(
                    HttpResponses.HttpResponseException.class,
                    () -> agent.toComputer().updateByXml(stream(agentXml)));
        }
        // The same refusal on the route a client actually takes. errorWithoutStack carries no exception
        // message, so the text is only visible in the response. An API token skips the crumb, which a raw
        // XML body has nowhere to carry as a parameter.
        JenkinsRule.WebClient wc = r.createWebClient().withBasicApiToken("admin");
        wc.setThrowExceptionOnFailingStatusCode(false);
        WebRequest post = new WebRequest(
                new java.net.URI(r.getURL() + "computer/xcpng-agent-1/config.xml").toURL(), HttpMethod.POST);
        post.setAdditionalHeader("Content-Type", "application/xml");
        post.setRequestBody(agentXml);
        WebResponse response = wc.getPage(post).getWebResponse();
        assertEquals(400, response.getStatusCode(), response.getContentAsString());
        assertTrue(
                response.getContentAsString().contains("cannot be replaced from its config.xml"),
                "the refusal should say why: " + response.getContentAsString());
        // A replace would have come out of readResolve marked reloaded and been reaped within about a second
        // (#354). Give it well past that, then check the original agent is still the registered one.
        Thread.sleep(3_000);
        assertSame(agent, r.jenkins.getNode("xcpng-agent-1"), "a refused replace must leave the agent in place");
        assertFalse(agent.isReloaded());
        assertFalse(
                fake.calls().stream().anyMatch(c -> c.startsWith("destroy")),
                "nothing may be destroyed: " + fake.calls());
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

    @Test
    void aNameIsNoLongerTrustedOnceItsTeardownIsOver(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake);
        r.jenkins.clouds.add(cloud);
        XcpngAgent agent = provisioned(r, cloud, "xcpng-agent-1");
        String xml = Jenkins.XSTREAM2.toXML(agent);

        // Control: the same definition, read back while the agent is alive, is still the issued one.
        assertTrue(fromDefinition(xml).isIssued());

        agent.terminate();

        assertFalse(
                fromDefinition(xml).isIssued(),
                "a node created later from the torn-down agent's definition must not inherit its trust");
    }

    @Test
    void anUnissuedNodeComingOnlineIsNotScrubbed(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake);
        r.jenkins.clouds.add(cloud);
        XcpngAgent original = provisioned(r, cloud, "xcpng-agent-1");

        XcpngAgent second = fromDefinition(Jenkins.XSTREAM2
                .toXML(original)
                .replace("<name>xcpng-agent-1</name>", "<name>xcpng-agent-second</name>"));
        r.jenkins.addNode(second);
        assertFalse(second.isIssued());

        scrubOnOnline(second.toComputer());
        assertEquals(0, scrubs(fake, original.getVmRef()), "an unissued node must not reach the pool: " + fake.calls());

        // Control: the agent the plugin issued is scrubbed through the same listener and fake.
        scrubOnOnline(original.toComputer());
        assertEquals(1, scrubs(fake, original.getVmRef()), "calls were " + fake.calls());
    }

    @Test
    void anAgentWhoseRecordCannotBeWrittenIsNotBuilt(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake);
        r.jenkins.clouds.add(cloud);
        // Control: with a writable store the same call builds an issued agent.
        XcpngAgent writable = cloud.createAgent(
                TEMPLATE, "xcpng-writable", new ProvisioningActivity.Id("xcpng", "t", "xcpng-writable"), false);
        assertTrue(writable.isIssued());

        // A non-empty directory where the store file goes: the atomic replace cannot land.
        File store = new File(r.jenkins.getRootDir(), XcpngIssuedAgentStore.class.getName() + ".xml");
        Files.deleteIfExists(store.toPath());
        assertTrue(store.mkdir());
        Files.writeString(new File(store, "occupied").toPath(), "x");

        assertThrows(
                IOException.class,
                () -> cloud.createAgent(
                        TEMPLATE,
                        "xcpng-unwritable",
                        new ProvisioningActivity.Id("xcpng", "t", "xcpng-unwritable"),
                        false));
        XcpngAgent same = fromDefinition(Jenkins.XSTREAM2
                .toXML(writable)
                .replace("<name>xcpng-writable</name>", "<name>xcpng-unwritable</name>"));
        assertFalse(same.isIssued(), "a record that was never written must not stay trusted in memory");
        assertEquals(0, clones(fake), "nothing is cloned for an agent that was never built");
    }

    private static void scrubOnOnline(Computer computer) throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        XcpngComputerListener listener = new XcpngComputerListener();
        listener.setScrubExecutor(executor);
        try {
            listener.onOnline(computer, TaskListener.NULL);
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "the scrub should finish");
        } finally {
            executor.shutdownNow();
        }
    }

    private static long scrubs(FakeHypervisorClient fake, String vmRef) {
        return fake.calls().stream()
                .filter(c -> c.equals("clearGuestSecret:" + vmRef))
                .count();
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
