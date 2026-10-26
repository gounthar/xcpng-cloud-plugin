package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.listeners.SaveableListener;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
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

    /** Core may still be launching an agent this test added; see {@link AgentLaunches}. */
    @AfterEach
    void awaitAgentLaunches() throws InterruptedException {
        AgentLaunches.awaitAll();
    }

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

    /** Whether core is still running this agent's launcher. */
    private static boolean isConnecting(XcpngAgent agent) {
        return agent.toComputer() instanceof SlaveComputer computer && computer.isConnecting();
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
        // Both halves matter. The reference is set between the clone and the start, so waiting on it alone
        // would read the call log while the first launcher was still writing to it.
        awaitTrue(
                () -> agent.getVmRef() != null && !isConnecting(agent),
                () -> "the first launch should have cloned a VM and returned");
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

    /**
     * A launch whose node is removed under it stops, rather than waiting out the online timeout for an agent
     * that no longer exists.
     *
     * <p>Not hypothetical, and not a case anyone designed: a controller restart reloads an agent that was
     * mid-boot, core connects its computer again, and the retention strategy reclaims it seconds later
     * because {@code readResolve} marked it reloaded. On the lab the launcher then sat in the wait for the
     * full five minutes and reported a launch failure for an agent whose teardown had already run.
     */
    @Test
    void aLaunchWhoseNodeIsRemovedStopsInsteadOfWaitingOutTheTimeout(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud =
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", null, 2, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        // A timeout far longer than this test may take, so finishing early can only mean the removal was
        // noticed. Waiting it out would fail the test by timing out instead.
        cloud.setOnlineWait(TimeUnit.MINUTES.toMillis(10), 10L);
        r.jenkins.clouds.add(cloud);

        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);
        r.jenkins.addNode(agent);
        awaitTrue(() -> agent.getVmRef() != null, () -> "the launcher should have cloned a VM");
        SlaveComputer computer = assertInstanceOf(SlaveComputer.class, agent.toComputer());

        CountDownLatch done = new CountDownLatch(1);
        Thread launching = new Thread(
                () -> {
                    try {
                        assertInstanceOf(XcpngLauncher.class, agent.getLauncher())
                                .launch(computer, TaskListener.NULL);
                    } catch (RuntimeException expected) {
                        // Removing the node fails the launch, which is the point.
                    } finally {
                        done.countDown();
                    }
                },
                "launch-under-test");
        launching.start();
        try {
            awaitTrue(
                    () -> launching.getState() == Thread.State.TIMED_WAITING,
                    () -> "the launcher should be parked in the online wait, was " + launching.getState());

            r.jenkins.removeNode(agent);

            assertTrue(
                    done.await(30, TimeUnit.SECONDS),
                    "the launch must give up when its node goes, not wait out the ten-minute timeout");

            // And it must not hold that against the template. Removing a node is a cancellation, not a
            // verdict on the golden image: a controller restart reloads mid-boot agents and the retention
            // strategy reclaims them, so counting these would withhold a healthy template from provisioning
            // after a few restarts, which is the opposite of what the backoff is for (#157).
            assertNull(
                    ExtensionList.lookupSingleton(XcpngTemplateBackoff.class)
                            .backoffFor("xcpng", LINUX_TEMPLATE.getTemplateName()),
                    "a node removed under the launcher must not be recorded as a template failure");
        } finally {
            launching.interrupt();
        }
    }

    /**
     * A node torn down while its clone is still in flight must not leave the clone behind (#305).
     *
     * <p>The teardown runs first and finds no VM, because the launcher has not stamped one yet; the clone then
     * comes back. Before the fix the launcher stamped it onto a node that no longer existed and started it, and
     * nothing in Jenkins knew about the running VM: not the node, not the leaked-VM store, not the instance cap.
     * The warm-pool drain gets here whenever it retires a spare that is still connecting.
     */
    @Test
    void aCloneThatReturnsAfterItsNodeWasTornDownIsDestroyedNotStarted(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = fake.holdClonesUntil(release);
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);
        r.jenkins.addNode(agent);
        assertTrue(held.await(30, TimeUnit.SECONDS), "the launcher should have reached the clone");

        // What drainSpare does to an idle spare, and an idle spare includes one still connecting.
        agent.terminate();
        assertNull(r.jenkins.getNode("xcpng-agent-1"), "the teardown removes the node");
        release.countDown();

        String clone = "vm/xcpng-agent-1/1";
        awaitTrue(
                () -> fake.calls().contains("destroyWithDisks:" + clone),
                () -> "the clone that came back to a torn-down node must be destroyed: " + fake.calls());
        assertFalse(
                fake.calls().contains("start:" + clone),
                "and never started, since no node is left to connect it: " + fake.calls());
        assertEquals(List.of("xcpng"), fake.destroyOwners(), "destroyed as this cloud's VM");
    }

    /**
     * If destroying that unadopted clone fails, it goes to the leaked-VM set: no node holds it, so the
     * warm-pool maintainer's sweep is the only thing left that will retry the destroy.
     */
    @Test
    void anUnadoptedCloneWhoseDestroyFailsIsRecordedAsLeaked(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").failDestroy();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = fake.holdClonesUntil(release);
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);
        r.jenkins.addNode(agent);
        assertTrue(held.await(30, TimeUnit.SECONDS), "the launcher should have reached the clone");

        agent.terminate();
        release.countDown();

        String clone = "vm/xcpng-agent-1/1";
        awaitTrue(
                () -> cloud.leakedVmRefs().contains(clone),
                () -> "a clone that could not be destroyed must be recorded as leaked: " + cloud.leakedVmRefs());
        assertTrue(fake.calls().contains("destroyWithDisks:" + clone), "the destroy was attempted first");
        assertFalse(fake.calls().contains("start:" + clone), "and the clone was never started: " + fake.calls());
    }

    /**
     * A clone the backend left behind (#310) that comes back to a node already torn down (#305). No node can
     * adopt it, so the launch teardown will never see it: the launcher must destroy it itself.
     */
    @Test
    void aCloneLeftBehindForATornDownNodeIsDestroyed(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").leaveCloneBehind();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = fake.holdClonesUntil(release);
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);
        r.jenkins.addNode(agent);
        assertTrue(held.await(30, TimeUnit.SECONDS), "the launcher should have reached the clone");

        agent.terminate();
        release.countDown();

        String clone = "vm/xcpng-agent-1/1";
        awaitTrue(
                () -> fake.calls().contains("destroyWithDisks:" + clone),
                () -> "a left-behind clone with no node to adopt it must be destroyed: " + fake.calls());
        assertFalse(fake.calls().contains("start:" + clone), "and never started: " + fake.calls());
        assertTrue(cloud.leakedVmRefs().isEmpty(), "a destroy that worked records nothing: " + cloud.leakedVmRefs());
    }

    /**
     * Adopting a clone saves the issued-agent store and the node, and neither save may run under the agent's
     * monitor: a save writes a file and fires save listeners, which may take core's locks, while the teardown
     * takes that monitor too. Updating the record in memory under the monitor is what keeps the two in step.
     */
    @Test
    void adoptingACloneSavesNothingUnderTheAgentsMonitor(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);
        SavesUnderAgentMonitor.watched = agent;
        r.jenkins.addNode(agent);

        awaitTrue(() -> agent.getVmRef() != null, () -> "the launcher should have adopted a clone: " + fake.calls());
        awaitTrue(() -> !isConnecting(agent), () -> "the launcher should have returned");
        assertTrue(
                SavesUnderAgentMonitor.saves.stream().anyMatch(s -> s instanceof XcpngIssuedAgentStore),
                "the control: the store save was seen at all, so an empty list below means something");
        assertEquals(
                List.of(),
                SavesUnderAgentMonitor.underMonitor,
                "saved while holding the agent's monitor: " + SavesUnderAgentMonitor.underMonitor);
    }

    @TestExtension("adoptingACloneSavesNothingUnderTheAgentsMonitor")
    public static class SavesUnderAgentMonitor extends SaveableListener {
        static volatile XcpngAgent watched;
        static final List<Saveable> saves = new CopyOnWriteArrayList<>();
        static final List<String> underMonitor = new CopyOnWriteArrayList<>();

        @Override
        public void onChange(Saveable o, XmlFile file) {
            XcpngAgent agent = watched;
            if (agent == null || !(o instanceof XcpngIssuedAgentStore || o == agent)) {
                return;
            }
            saves.add(o);
            if (Thread.holdsLock(agent)) {
                underMonitor.add(o.getClass().getSimpleName());
            }
        }
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

    /**
     * The New Node form must not offer this launcher (#158).
     *
     * <p>Asked the way the form itself asks. {@code DumbSlave.DescriptorImpl.computerLauncherDescriptors} is
     * what {@code config.jelly} iterates to build the launch-method dropdown, so a descriptor absent from that
     * list is absent from the dropdown. Picking it there could only fail: this launcher has no
     * {@code @DataBoundConstructor} for the form to bind, and one built that way would carry no template and
     * no cloud.
     */
    @Test
    void theNewNodeFormDoesNotOfferThisLauncher(JenkinsRule r) {
        List<String> offered =
                r.jenkins.getDescriptorByType(DumbSlave.DescriptorImpl.class).computerLauncherDescriptors(null).stream()
                        .map(d -> d.clazz.getName())
                        .toList();

        assertFalse(
                offered.contains(XcpngLauncher.class.getName()),
                () -> "the New Node launch-method dropdown must not offer a launcher it cannot bind, but got "
                        + offered);
    }

    /**
     * And it must still have a descriptor, which is the half that stops the fix being "delete it".
     *
     * <p>{@code AbstractDescribableImpl.getDescriptor} goes through {@code Jenkins.getDescriptorOrDie}, so a
     * launcher with no descriptor throws an {@code AssertionError} the first time a view renders the node it
     * is attached to, the agent configuration page included. Hiding it from the dropdown must not cost that.
     */
    @Test
    void anAgentStillResolvesItsLauncherDescriptor(JenkinsRule r) throws Exception {
        XcpngCloud cloud = cloudBackedBy(r, new FakeHypervisorClient());
        XcpngAgent agent = cloud.createAgent(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"), false);

        Descriptor<ComputerLauncher> descriptor = agent.getLauncher().getDescriptor();

        assertNotNull(descriptor, "an agent must be able to resolve its launcher's descriptor");
        assertEquals(
                XcpngLauncher.class,
                descriptor.clazz,
                "the descriptor an agent resolves must be the one describing its own launcher");
        assertSame(
                descriptor,
                agent.getLauncher().getDescriptor(),
                "a descriptor is a singleton; two lookups must not hand back objects that compare unequal");
    }
}
