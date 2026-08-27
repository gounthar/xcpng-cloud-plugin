package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The ordering {@link XcpngLauncher} exists to establish: the node comes first and the VM second.
 *
 * <p>The clone, the seed and the start used to run inside {@code provision}, which forced the cloud to
 * register the node itself so the inbound agent had something to dial in to — and that is what let core's
 * {@code NodeProvisioner} register the same node a second time (#145). What is asserted here is the shape
 * that replaced it, not the clone itself: {@code XcpngProvisionTest} covers what the launcher does to the
 * pool once it runs.
 */
@WithJenkins
class XcpngLauncherTest {

    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

    private static XcpngCloud cloudBackedBy(JenkinsRule r, FakeHypervisorClient fake) {
        XcpngCloud cloud =
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", null, 2, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        // The fake agent never dials in, so the launcher would otherwise sit out its whole online wait.
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static ProvisioningActivity.Id activityId(String nodeName) {
        return new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", nodeName);
    }

    private static void awaitTrue(BooleanSupplier condition, Supplier<String> message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail(message.get());
    }

    private static long cloneCount(FakeHypervisorClient fake) {
        return fake.calls().stream()
                .filter(c -> c.startsWith("cloneFromTemplate:"))
                .count();
    }

    /**
     * The whole point, in one assertion: a planned node arrives with no VM behind it. Everything else in this
     * change follows from that, including the nullable {@code vmRef} and the reservations that hold the
     * instance cap while core has not registered the node yet.
     */
    @Test
    void aPlannedAgentIsHandedOverBeforeItsVmExists(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);

        NodeProvisioner.PlannedNode planned = cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 1)
                .iterator()
                .next();

        assertTrue(planned.future.isDone(), "the future must settle at once, so core registers the node itself");
        XcpngAgent agent = assertInstanceOf(XcpngAgent.class, planned.future.get(30, TimeUnit.SECONDS));
        assertNull(agent.getVmRef(), "the VM is the launcher's job, and the launcher has not run yet");
        assertTrue(fake.calls().isEmpty(), "planning must not touch the pool: " + fake.calls());
    }

    /**
     * {@code JNLPLauncher} reports that it cannot launch, because it has nothing to do. This one does.
     *
     * <p>Overriding it is not what gets {@code launch} called on the cloud path — {@code SlaveComputer._connect}
     * never consults it — but {@code RetentionStrategy.Always} and {@code Demand} both guard on it, and so does
     * the UI's launch button. kubernetes-plugin's launcher overrides it for the same reasons.
     */
    @Test
    void theLauncherReportsThatItCanLaunch(JenkinsRule r) throws Exception {
        XcpngCloud cloud = cloudBackedBy(r, new FakeHypervisorClient("jenkins-golden-debian"));

        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);

        XcpngLauncher launcher = assertInstanceOf(XcpngLauncher.class, agent.getLauncher());
        assertTrue(launcher.isLaunchSupported(), "a launcher with a VM to build must say it can launch");
        assertFalse(
                new JNLPLauncher().isLaunchSupported(),
                "the base class says it cannot, which is the behaviour this overrides");
        assertEquals(LINUX_TEMPLATE, launcher.getTemplate(), "the launcher clones the template it was built for");
    }

    /**
     * A reconnect must not clone a second VM. Core calls {@code launch} again whenever it reconnects a
     * computer, and the agent's name is already taken by the running clone: a second one would leak the first
     * and present the controller with two agents claiming the same identity.
     */
    @Test
    void aReconnectWaitsForTheExistingVmRatherThanCloningAnother(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);
        r.jenkins.addNode(agent);
        awaitTrue(() -> agent.getVmRef() != null, () -> "the first launch should have cloned a VM");
        assertEquals(1, cloneCount(fake), "one launch, one clone: " + fake.calls());
        String vmRef = agent.getVmRef();

        SlaveComputer computer = assertInstanceOf(SlaveComputer.class, agent.toComputer());
        assertInstanceOf(XcpngLauncher.class, agent.getLauncher()).launch(computer, TaskListener.NULL);

        assertEquals(1, cloneCount(fake), "a relaunch must reuse the VM it already built: " + fake.calls());
        assertEquals(vmRef, agent.getVmRef(), "and must not replace the reference to it");
    }

    /**
     * An agent whose cloud has been deleted or renamed cannot be provisioned against anything, so the launch
     * fails rather than half-building something. Nothing reaches the pool: there is no session to open, and
     * the node is left to the idle net, which reclaims it through the connection snapshot on the agent.
     */
    @Test
    void aLaunchWithNoCloudToProvisionAgainstFailsAndTouchesNoPool(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);
        // Deleted between the plan and the launch, which is what renaming a cloud looks like to an agent
        // holding its old name.
        r.jenkins.clouds.remove(cloud);
        r.jenkins.addNode(agent);

        SlaveComputer computer = assertInstanceOf(SlaveComputer.class, agent.toComputer());
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> assertInstanceOf(XcpngLauncher.class, agent.getLauncher()).launch(computer, TaskListener.NULL));

        assertTrue(
                thrown.getMessage().contains("xcpng"),
                "the failure must name the cloud that is gone: " + thrown.getMessage());
        assertTrue(fake.calls().isEmpty(), "a launch with no cloud must not reach a pool: " + fake.calls());
        assertNull(agent.getVmRef(), "and must not claim to have built anything");
        assertNotNull(r.jenkins.getNode("xcpng-agent-1"), "the node stays for the idle net to reclaim");
    }

    /** A launcher is only ever attached to an {@link XcpngAgent}; anything else is a configuration error. */
    @Test
    void aLauncherOnSomethingOtherThanAnXcpngAgentRefusesToRun(JenkinsRule r) throws Exception {
        Node dumb = r.createSlave();
        SlaveComputer computer = assertInstanceOf(SlaveComputer.class, dumb.toComputer());

        assertThrows(
                IllegalStateException.class,
                () -> new XcpngLauncher(LINUX_TEMPLATE).launch(computer, TaskListener.NULL),
                "a launcher with no XCP-ng agent behind it has nothing to provision");
    }
}
