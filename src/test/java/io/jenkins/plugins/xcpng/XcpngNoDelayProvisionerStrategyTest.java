package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * {@link XcpngNoDelayProvisionerStrategy}: a waiting build gets its clone within seconds rather than after
 * core's load averages catch up, and a booting agent is subtracted from the demand once, not twice.
 *
 * <p>The Jenkins tests wait ten seconds for a clone. Core's periodic provisioner does not run its first round
 * until {@code LoadStatistics.CLOCK * 10} after startup (100 seconds), so every round inside that window is
 * one this strategy or a test asked for. That alone would make a control that merely waits meaningless, so
 * {@link #withTheStrategyDisabledTheSameBuildIsLeftToCore} forces rounds throughout its window: it shows
 * that core's standard strategy, given every chance, still does not provision for the reported case.
 */
@WithJenkins
class XcpngNoDelayProvisionerStrategyTest {

    private static final String LABEL = "xcpng-linux";

    private static final XcpngTemplate LINUX_TEMPLATE = new XcpngTemplate("jenkins-golden-debian", LABEL, 2, 2048);

    /** How long a build may wait for its clone before the strategy counts as not having acted. */
    private static final long WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(10);

    // ---- The arithmetic ----

    @Test
    void theWorkloadLeavesTheCloudsOwnBootingAgentsForItToSubtract() {
        // Two builds waiting, one agent booting, and it is the asked cloud's own: both builds are offered,
        // because XcpngCloud.provision subtracts its own booting agents itself.
        assertEquals(2, XcpngNoDelayProvisionerStrategy.workloadToProvision(2, 0, 1, 1, 0, 0));
        // The same agent booting on another cloud, or for another template: this cloud never sees it, so it
        // is subtracted here.
        assertEquals(1, XcpngNoDelayProvisionerStrategy.workloadToProvision(2, 0, 1, 0, 0, 0));
        // A free executor and a planned one each take a build.
        assertEquals(0, XcpngNoDelayProvisionerStrategy.workloadToProvision(2, 1, 0, 0, 1, 0));
        assertEquals(1, XcpngNoDelayProvisionerStrategy.workloadToProvision(2, 0, 0, 0, 0, 1));
        // The cloud's count read after the snapshot can exceed it; that is not extra demand.
        assertEquals(1, XcpngNoDelayProvisionerStrategy.workloadToProvision(1, 0, 0, 1, 0, 0));
        // Idle capacity beyond the demand is not a negative request.
        assertTrue(XcpngNoDelayProvisionerStrategy.workloadToProvision(0, 2, 0, 0, 0, 0) <= 0);
    }

    @Test
    void aBootingAgentStillCountsAsCoverForTheDecision() {
        // One build, one agent booting for it: nothing else for the remaining strategies to do.
        assertTrue(XcpngNoDelayProvisionerStrategy.isCovered(1, 0, 1, 0, 0));
        // Two builds, one agent booting: not covered, so core's strategy is still consulted.
        assertFalse(XcpngNoDelayProvisionerStrategy.isCovered(2, 0, 1, 0, 0));
    }

    // ---- Against a live Jenkins ----

    /**
     * A cloud over {@code fake} whose launcher keeps waiting for the agent to come online, as it does in
     * production. The fake agent never connects, so a launched agent stays in the connecting state for the
     * whole test, which is the state a booting clone is in for about 35 seconds on the lab pool.
     */
    private static XcpngCloud bootingCloudBackedBy(FakeHypervisorClient fake, int maxInstances) {
        return bootingCloudBackedBy("xcpng", fake, maxInstances, LINUX_TEMPLATE);
    }

    private static XcpngCloud bootingCloudBackedBy(
            String name, FakeHypervisorClient fake, int maxInstances, XcpngTemplate template) {
        XcpngCloud cloud =
                new XcpngCloud(name, "https://pool.example.test", "cred", null, maxInstances, List.of(template));
        cloud.setClientFactory(c -> fake);
        cloud.setOnlineWait(TimeUnit.SECONDS.toMillis(60), 10L);
        return cloud;
    }

    private static long cloneCount(FakeHypervisorClient fake) {
        return fake.calls().stream()
                .filter(c -> c.startsWith("cloneFromTemplate:"))
                .count();
    }

    private static boolean waitFor(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static void awaitWithin(BooleanSupplier condition, long millis, Supplier<String> message)
            throws InterruptedException {
        if (!waitFor(condition, millis)) {
            fail(message.get());
        }
    }

    private static FreeStyleProject labelled(JenkinsRule r, String name) throws Exception {
        FreeStyleProject project = r.createFreeStyleProject(name);
        project.setAssignedLabel(Label.get(LABEL));
        return project;
    }

    /** Drop every queued item and tear down every XCP-ng agent, so no launcher outlives the test. */
    private static void cleanUp(JenkinsRule r) throws Exception {
        r.jenkins.getQueue().clear();
        // Stop the blocking build too, so it does not outlive the test and race JenkinsRule's teardown.
        for (Computer computer : r.jenkins.getComputers()) {
            for (Executor executor : computer.getExecutors()) {
                if (executor.isBusy()) {
                    executor.interrupt();
                }
            }
        }
        r.waitUntilNoActivityUpTo((int) WINDOW_MILLIS);
        for (Node node : r.jenkins.getNodes()) {
            if (node instanceof XcpngAgent agent) {
                agent.terminate();
            }
        }
    }

    /**
     * The reported case: an agent for the label exists and is busy, a second build queues behind it, and
     * with only core's strategy nothing is requested until the queue average climbs.
     */
    private static void queueBehindABusyAgent(JenkinsRule r) throws Exception {
        DumbSlave busy = r.createOnlineSlave(Label.get(LABEL));
        FreeStyleProject first = labelled(r, "first");
        first.getBuildersList().add(new SleepBuilder(TimeUnit.MINUTES.toMillis(5)));
        first.scheduleBuild2(0).waitForStart();
        // Read the agent's executor rather than the build's getBuiltOn(), which answers the built-in node
        // until the build has recorded where it runs.
        awaitWithin(
                () -> busy.toComputer().countBusy() == 1,
                WINDOW_MILLIS,
                () -> "the first build must occupy the existing agent");
        labelled(r, "second").scheduleBuild2(0);
    }

    @Test
    void aBuildWaitingBehindABusyAgentGetsItsCloneWithinSeconds(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        r.jenkins.clouds.add(bootingCloudBackedBy(fake, 2));
        try {
            queueBehindABusyAgent(r);
            awaitWithin(
                    () -> cloneCount(fake) == 1 && isAnAgentConnecting(r),
                    WINDOW_MILLIS,
                    () -> "the waiting build should have had a clone requested for it at once: " + fake.calls());
            // cloud-stats records a provisioning activity from onStarted, which core's strategy fires and this
            // one must fire too.
            // Core fires onStarted after every provision() call, empty ones included, and so does this
            // strategy; a later round with the agent already booting adds an empty call. What must hold is
            // that the one planned node was announced, once.
            assertEquals(
                    1,
                    StartedRecorder.nodes.size(),
                    "listeners must hear of the planned node once: " + StartedRecorder.nodes);
            assertTrue(
                    StartedRecorder.nodes.get(0).startsWith("xcpng:"),
                    "announced for this cloud: " + StartedRecorder.nodes);
        } finally {
            cleanUp(r);
        }
    }

    @Test
    void withTheStrategyDisabledTheSameBuildIsLeftToCore(JenkinsRule r) throws Exception {
        // The control for the test above: same setup, strategy off. Core's periodic round would not run inside
        // the window at all, so rounds are forced here, several a second: if a clone appears, core's own
        // strategy serves the reported case promptly and the test above proves nothing.
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        r.jenkins.clouds.add(bootingCloudBackedBy(fake, 2));
        try {
            System.setProperty(XcpngNoDelayProvisionerStrategy.DISABLE_PROPERTY, "true");
            queueBehindABusyAgent(r);
            assertFalse(
                    waitFor(
                            () -> {
                                Label.get(LABEL).nodeProvisioner.suggestReviewNow();
                                return cloneCount(fake) > 0;
                            },
                            WINDOW_MILLIS),
                    "core's own strategy should not have provisioned, even with rounds forced: " + fake.calls());
        } finally {
            System.clearProperty(XcpngNoDelayProvisionerStrategy.DISABLE_PROPERTY);
            cleanUp(r);
        }
    }

    @Test
    void aSecondBuildArrivingWhileTheFirstAgentBootsGetsItsOwnClone(JenkinsRule r) throws Exception {
        // The double subtraction #265 warns about. The first agent is booting, so core counts it as a
        // connecting executor and XcpngCloud.provision counts it as already building. Subtract both and the
        // second build is offered to the cloud as zero work, and waits for core's averages after all.
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        // A cap of 3 for two builds, so over-provisioning would show as a third clone rather than be
        // hidden by the cap.
        r.jenkins.clouds.add(bootingCloudBackedBy(fake, 3));
        try {
            labelled(r, "first").scheduleBuild2(0);
            awaitWithin(
                    () -> cloneCount(fake) == 1 && isAnAgentConnecting(r),
                    WINDOW_MILLIS,
                    () -> "the first build should have an agent booting: " + fake.calls());

            labelled(r, "second").scheduleBuild2(0);
            awaitWithin(
                    () -> cloneCount(fake) == 2,
                    WINDOW_MILLIS,
                    () -> "the second build should get its own clone while the first agent boots: " + fake.calls());

            // And neither booting agent may be cloned again: that is the 2026-08-27 double clone, which the
            // cloud's own subtraction is what prevents. Force a few extra rounds rather than wait for them.
            nudge(3);
            assertEquals(2, cloneCount(fake), "two builds, two agents booting, no third clone: " + fake.calls());
        } finally {
            cleanUp(r);
        }
    }

    @Test
    void anAgentBootingOnAnotherCloudIsNotClonedAgainByTheNextOne(JenkinsRule r) throws Exception {
        // Two clouds serve the label; the first has room for one agent. Once that agent is booting the first
        // cloud is at its cap, so the next round reaches the second cloud. Its own plan() sees nothing of its
        // own building and cannot know about the first cloud's agent, so unless the strategy subtracts
        // connecting executors that are not the asked cloud's own, the one waiting build gets two clones.
        FakeHypervisorClient first = new FakeHypervisorClient("jenkins-golden-debian");
        FakeHypervisorClient second = new FakeHypervisorClient("jenkins-golden-debian");
        r.jenkins.clouds.add(bootingCloudBackedBy("xcpng", first, 1, LINUX_TEMPLATE));
        r.jenkins.clouds.add(bootingCloudBackedBy("xcpng-overflow", second, 2, LINUX_TEMPLATE));
        try {
            labelled(r, "only").scheduleBuild2(0);
            awaitWithin(
                    () -> cloneCount(first) == 1 && isAnAgentConnecting(r),
                    WINDOW_MILLIS,
                    () -> "the first cloud should have an agent booting: " + first.calls());
            nudge(3);
            assertEquals(
                    0,
                    cloneCount(second),
                    "one build, one agent booting elsewhere, no second clone: " + second.calls());
        } finally {
            cleanUp(r);
        }
    }

    @Test
    void aBuildArrivingWhileAWarmSpareBootsWaitsForThatSpare(JenkinsRule r) throws Exception {
        // The warm-pool promise in the README: a spare still booting is capacity, so a build that arrives
        // meanwhile waits for it instead of getting a clone of its own.
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngTemplate warm = new XcpngTemplate("jenkins-golden-debian", LABEL, 2, 2048);
        warm.setMinInstances(1);
        XcpngCloud cloud = bootingCloudBackedBy("xcpng", fake, 3, warm);
        r.jenkins.clouds.add(cloud);
        try {
            cloud.reconcileWarmPool();
            awaitWithin(
                    () -> cloneCount(fake) == 1 && isAnAgentConnecting(r),
                    WINDOW_MILLIS,
                    () -> "the warm pool should have a spare booting: " + fake.calls());
            labelled(r, "arrives-during-boot").scheduleBuild2(0);
            nudge(3);
            assertEquals(1, cloneCount(fake), "the booting spare covers the build, no second clone: " + fake.calls());
        } finally {
            cleanUp(r);
        }
    }

    /** Force a few extra provisioning rounds rather than wait out the provisioner's own period. */
    private static void nudge(int rounds) throws InterruptedException {
        for (int i = 0; i < rounds; i++) {
            Label.get(LABEL).nodeProvisioner.suggestReviewNow();
            Thread.sleep(1_000);
        }
    }

    @Test
    void aLabelNoXcpngCloudServesIsLeftToTheOtherStrategies(JenkinsRule r) throws Exception {
        // A label only another cloud serves, with its demand already covered by a node that cloud is still
        // planning. This strategy must not answer PROVISIONING_COMPLETED for it: that would end the round
        // before any lower-ordinal strategy, another plugin's included, is consulted.
        r.jenkins.clouds.add(bootingCloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 2));
        r.jenkins.clouds.add(new NeverDeliveringCloud());
        Label other = Label.get(NeverDeliveringCloud.LABEL);
        FreeStyleProject project = r.createFreeStyleProject("other");
        project.setAssignedLabel(other);
        try {
            project.scheduleBuild2(0);
            // First round: nothing is planned yet, core's strategy asks the other cloud, and a node that never
            // arrives is now pending, so every later round sees the build as covered.
            awaitWithin(
                    () -> {
                        other.nodeProvisioner.suggestReviewNow();
                        return NeverDeliveringCloud.provisioned.get() > 0;
                    },
                    WINDOW_MILLIS,
                    () -> "core's strategy should have asked the other cloud for a node");
            ConsultedRecorder.labels.clear();
            awaitWithin(
                    () -> {
                        other.nodeProvisioner.suggestReviewNow();
                        return ConsultedRecorder.labels.contains(NeverDeliveringCloud.LABEL);
                    },
                    WINDOW_MILLIS,
                    () -> "a lower-ordinal strategy must still be consulted for a label no XCP-ng cloud serves");
        } finally {
            cleanUp(r);
        }
    }

    /** Records every planned node {@code onStarted} announces, as {@code cloud:node}. */
    @TestExtension("aBuildWaitingBehindABusyAgentGetsItsCloneWithinSeconds")
    public static class StartedRecorder extends CloudProvisioningListener {
        static final List<String> nodes = new CopyOnWriteArrayList<>();

        @Override
        public void onStarted(Cloud cloud, Label label, Collection<NodeProvisioner.PlannedNode> plannedNodes) {
            for (NodeProvisioner.PlannedNode node : plannedNodes) {
                nodes.add(cloud.name + ":" + node.displayName);
            }
        }
    }

    /**
     * A strategy consulted after this plugin's, recording the labels it is asked about. Whether core's standard
     * strategy runs before or after it does not matter: once the demand is covered by a planned node, core's
     * has nothing to do and passes the round on, so only an early PROVISIONING_COMPLETED keeps this one out.
     */
    @TestExtension("aLabelNoXcpngCloudServesIsLeftToTheOtherStrategies")
    public static class ConsultedRecorder extends NodeProvisioner.Strategy {
        static final Set<String> labels = ConcurrentHashMap.newKeySet();

        @NonNull
        @Override
        public NodeProvisioner.StrategyDecision apply(@NonNull NodeProvisioner.StrategyState state) {
            if (state.getLabel() != null) {
                labels.add(state.getLabel().getName());
            }
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
    }

    /** Serves one label and plans a node for it that never arrives, so its demand reads as covered. */
    private static class NeverDeliveringCloud extends Cloud {
        static final String LABEL = "not-xcpng";
        static final AtomicInteger provisioned = new AtomicInteger();

        NeverDeliveringCloud() {
            super("never-delivering");
        }

        @Override
        public boolean canProvision(CloudState state) {
            return state.getLabel() != null && LABEL.equals(state.getLabel().getName());
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
            provisioned.incrementAndGet();
            return List.of(new NodeProvisioner.PlannedNode("never", new CompletableFuture<>(), 1));
        }
    }

    private static boolean isAnAgentConnecting(JenkinsRule r) {
        for (Node node : r.jenkins.getNodes()) {
            if (node instanceof XcpngAgent agent
                    && agent.toComputer() instanceof SlaveComputer computer
                    && computer.isConnecting()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theStrategyRunsBeforeCoresAndTheQueueListenerIsRegistered(JenkinsRule r) {
        // Strategies are consulted in ordinal order and the first PROVISIONING_COMPLETED ends the round, so
        // this one comes first: a waiting build is covered before core's averages are consulted about it.
        List<NodeProvisioner.Strategy> strategies = r.jenkins.getExtensionList(NodeProvisioner.Strategy.class);
        assertInstanceOf(XcpngNoDelayProvisionerStrategy.class, strategies.get(0), "strategy order: " + strategies);
        assertEquals(
                1,
                QueueListener.all().stream()
                        .filter(XcpngNoDelayProvisionerStrategy.FastProvisioning.class::isInstance)
                        .count());
    }
}
