package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Executor;
import hudson.slaves.AbstractCloudComputer;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The retention and teardown lifecycle: the idle-net math and its warm-spare exemption, the warm to used
 * flip on task acceptance, the single-use reap on task completion, the window between deciding to reclaim an
 * agent and destroying its VM, and the async teardown itself, guard and error paths included. The reaps are
 * driven on an injected executor, the idle-timeout math off an injected clock, and the busy check off an
 * injected probe, so each path runs to completion within the test rather than on the remoting pool.
 */
@WithJenkins
class XcpngRetentionStrategyTest {

    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

    private static final int IDLE_MINUTES = 10;

    /** An {@link AbstractCloudComputer} that reports online, to stand in for a spare that has connected. */
    private static final class OnlineComputer extends XcpngComputer {
        OnlineComputer(XcpngAgent agent) {
            super(agent);
        }

        @Override
        public boolean isOffline() {
            return false;
        }
    }

    /** A cloud whose clients are the given fake, registered so agents can resolve it back by name. */
    private static XcpngCloud cloudBackedBy(JenkinsRule r, FakeHypervisorClient fake) {
        XcpngCloud cloud =
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", false, 3, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static ProvisioningActivity.Id activityId(String nodeName) {
        return new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", nodeName);
    }

    /** Provision an agent (warm or on-demand) through the cloud, so it carries a real VM ref and cloud name. */
    private static XcpngAgent agent(XcpngCloud cloud, String name, boolean warm) throws Exception {
        return (XcpngAgent) cloud.provisionNode(LINUX_TEMPLATE, name, activityId(name), warm);
    }

    private static long destroyCount(FakeHypervisorClient fake) {
        return fake.calls().stream()
                .filter(c -> c.startsWith("destroyWithDisks:"))
                .count();
    }

    // ---- The pure exemption rule and the warm flag (unchanged behaviour) ----

    /**
     * The exemption keeps a spare hot only when it is warm AND online AND still attached to a live
     * cloud. Every other combination falls through to the idle net, so a spare can never leak: an
     * offline spare that never connected is reaped, and one whose cloud was deleted is reaped.
     */
    @Test
    void exemptOnlyWhenWarmOnlineAndCloudPresent(JenkinsRule r) {
        assertTrue(XcpngRetentionStrategy.exemptFromIdleReap(true, true, true), "warm, online, cloud present");
        assertFalse(XcpngRetentionStrategy.exemptFromIdleReap(true, true, false), "cloud deleted must not be exempt");
        assertFalse(XcpngRetentionStrategy.exemptFromIdleReap(true, false, true), "offline spare must be reapable");
        assertFalse(XcpngRetentionStrategy.exemptFromIdleReap(false, true, true), "a used agent is never exempt");
        assertFalse(XcpngRetentionStrategy.exemptFromIdleReap(false, false, false), "the spent, offline case");
    }

    /** A warm spare reports warm until it accepts work, then markUsed() flips it to single-use for good. */
    @Test
    void markUsedClearsTheWarmFlag(JenkinsRule r) throws Exception {
        XcpngTemplate template = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", "xcpng-warm-1");
        XcpngAgent spare = new XcpngAgent("xcpng-warm-1", "xcpng", "vm/xcpng-warm-1/1", template, 10, id, true);

        assertTrue(spare.isWarm(), "a spare is warm at birth");
        spare.markUsed();
        assertFalse(spare.isWarm(), "accepting work clears the warm flag");
        spare.markUsed();
        assertFalse(spare.isWarm(), "markUsed is idempotent");
    }

    // ---- The idle net in check() ----

    /**
     * An agent that has only just gone idle is nowhere near its timeout, so check() must leave it alone.
     * This is the guard against an off-by-unit conversion: comparing the elapsed millis against the raw
     * minute count (no MINUTES.toMillis) would make a few-millisecond-old agent look ten minutes idle and
     * reap it on the spot.
     */
    @Test
    void checkDoesNotReapAnAgentBeforeItsIdleTimeout(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, agent.toComputer());

        long idleStart = computer.getIdleStartMilliseconds();
        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);
        // Pin the clock 30 seconds past idle start: comfortably within the ten-minute timeout, yet far past
        // the raw minute count. A conversion-less comparison (idleMillis > idleMinutes, treating 10 minutes as
        // 10 millis) would wrongly reap here, so this value is what makes the off-by-unit catchable rather than
        // resting on however many milliseconds happen to elapse before the check runs.
        strategy.setClock(() -> idleStart + 30_000L);
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        strategy.setReapExecutor(reaps);
        try {
            strategy.check(computer);

            reaps.shutdown();
            assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "any (wrongly) scheduled reap should finish");
            assertEquals(
                    0, destroyCount(fake), "a freshly-idle agent is well within its timeout and must not be reaped");
        } finally {
            reaps.shutdownNow();
        }
    }

    /**
     * Once the elapsed idle time is past the timeout, check() reaps: the VM is destroyed with its disks.
     * The clock is pinned just past {@code idleStart + MINUTES.toMillis(idleMinutes)}, so a conversion using
     * too large a unit (hours, say) would leave the agent short of the deadline and this would catch it.
     */
    @Test
    void checkReapsAnIdleAgentPastTheTimeout(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, agent.toComputer());

        long idleStart = computer.getIdleStartMilliseconds();
        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);
        strategy.setClock(() -> idleStart + TimeUnit.MINUTES.toMillis(IDLE_MINUTES) + 5000L);
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        strategy.setReapExecutor(reaps);
        try {
            strategy.check(computer);

            reaps.shutdown();
            assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "the idle reap should finish");
            assertTrue(
                    fake.calls().contains("destroyWithDisks:" + agent.getVmRef()),
                    "an idle agent past its timeout must have its VM destroyed: " + fake.calls());
        } finally {
            reaps.shutdownNow();
        }
    }

    /**
     * A warm spare that has connected is exempt from the idle net even when it is well past the timeout: that
     * is the whole point of the warm pool. Deleting the exemption short-circuit in check() would let this
     * spare be idle-reaped, silently defeating the feature, and this test is what would notice.
     */
    @Test
    void checkExemptsAWarmOnlineSpareFromTheIdleNet(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent spare = agent(cloud, "xcpng-warm-1", true);
        r.jenkins.addNode(spare);
        OnlineComputer online = new OnlineComputer(spare);

        long idleStart = online.getIdleStartMilliseconds();
        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);
        strategy.setClock(() -> idleStart + TimeUnit.MINUTES.toMillis(IDLE_MINUTES) + 5000L);
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        strategy.setReapExecutor(reaps);
        try {
            strategy.check(online);

            reaps.shutdown();
            assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "no reap should have been scheduled");
            assertEquals(
                    0, destroyCount(fake), "a warm, online spare must be exempt from the idle net: " + fake.calls());
        } finally {
            reaps.shutdownNow();
        }
    }

    /**
     * The same online agent past the same timeout, but used rather than warm, is not exempt: check() reaps
     * it. Together with the exemption test above, this proves the warm flag is the discriminator, not some
     * incidental difference in the two setups.
     */
    @Test
    void checkReapsANonWarmOnlineAgentPastTheTimeout(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);
        OnlineComputer online = new OnlineComputer(agent);

        long idleStart = online.getIdleStartMilliseconds();
        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);
        strategy.setClock(() -> idleStart + TimeUnit.MINUTES.toMillis(IDLE_MINUTES) + 5000L);
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        strategy.setReapExecutor(reaps);
        try {
            strategy.check(online);

            reaps.shutdown();
            assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "the idle reap should finish");
            assertTrue(
                    fake.calls().contains("destroyWithDisks:" + agent.getVmRef()),
                    "a used, online agent past its timeout must still be reaped: " + fake.calls());
        } finally {
            reaps.shutdownNow();
        }
    }

    // ---- taskAccepted / taskCompleted wiring ----

    /**
     * taskAccepted must flip the executor's owning node from warm to used. Match on the computer rather than
     * its node, or on the wrong node, and a spare stays warm forever, exempt from the idle net after its
     * build, and its VM leaks.
     */
    @Test
    void taskAcceptedFlipsTheOwningSpareToUsed(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent spare = agent(cloud, "xcpng-warm-1", true);
        r.jenkins.addNode(spare);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, spare.toComputer());
        Executor executor = computer.getExecutors().get(0);
        assertTrue(spare.isWarm(), "the spare is warm before it accepts work");

        new XcpngRetentionStrategy(IDLE_MINUTES).taskAccepted(executor, null);

        assertFalse(spare.isWarm(), "accepting a build must clear the warm flag on the owning agent");
    }

    /**
     * Accepting a build must also stop the computer taking any further one. These agents are single-use, so a
     * second build would run on a VM that is no longer pristine, and would be killed by the teardown the first
     * build's completion reap has already started. Leaving that to the completion reap is too late: the
     * computer stays assignable in the window between the build finishing and the reap taking effect.
     */
    @Test
    void taskAcceptedStopsTheComputerTakingFurtherWork(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent spare = agent(cloud, "xcpng-warm-1", true);
        r.jenkins.addNode(spare);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, spare.toComputer());
        Executor executor = computer.getExecutors().get(0);
        assertTrue(computer.isAcceptingTasks(), "the spare accepts work before it has any");

        new XcpngRetentionStrategy(IDLE_MINUTES).taskAccepted(executor, null);

        assertFalse(computer.isAcceptingTasks(), "a single-use agent must refuse work the moment it has some");
    }

    /** A build finishing reaps its single-use agent: the VM is destroyed with its disks. */
    @Test
    void taskCompletedReapsTheAgent(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, agent.toComputer());
        Executor executor = computer.getExecutors().get(0);

        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        strategy.setReapExecutor(reaps);
        try {
            strategy.taskCompleted(executor, null, 0L);

            reaps.shutdown();
            assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "the completion reap should finish");
            assertTrue(
                    fake.calls().contains("destroyWithDisks:" + agent.getVmRef()),
                    "a completed build must reap its single-use agent: " + fake.calls());
        } finally {
            reaps.shutdownNow();
        }
    }

    /**
     * The completion reap must fire even though the computer reads busy: the executor still holds the
     * finished executable while its listeners run. Applying the idle re-check to this caller too would make
     * every single-use agent abandon its own reap and leak its VM, which is why the two entry points differ.
     */
    @Test
    void taskCompletedReapsEvenThoughTheFinishedBuildStillReadsAsWork(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, agent.toComputer());
        Executor executor = computer.getExecutors().get(0);

        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);
        strategy.setIdleProbe(c -> false);
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        strategy.setReapExecutor(reaps);
        try {
            strategy.taskCompleted(executor, null, 0L);

            reaps.shutdown();
            assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "the completion reap should finish");
            assertTrue(
                    fake.calls().contains("destroyWithDisks:" + agent.getVmRef()),
                    "a busy-reading computer must not stop the completion reap: " + fake.calls());
        } finally {
            reaps.shutdownNow();
        }
    }

    // ---- The accept-versus-reap window ----

    /**
     * A build assigned after the drain passed its idle check, but before the teardown ran, must keep its VM.
     * The teardown is queued behind a gate so the build can land in exactly that window; the reap has to
     * notice and abandon itself rather than hard-destroying the VM under a running build.
     *
     * <p>The bail-out is a deferral, not a reprieve: the second half asserts the in-flight guard was released,
     * so the agent that took the build is still reclaimable afterwards. A bail that left the guard set would
     * trade a killed build for a leaked VM.
     */
    @Test
    void reapAbandonsTheTeardownWhenABuildGetsInFirst(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent spare = agent(cloud, "xcpng-warm-1", true);
        r.jenkins.addNode(spare);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, spare.toComputer());

        AtomicBoolean idle = new AtomicBoolean(true);
        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);
        strategy.setIdleProbe(c -> idle.get());

        // One worker, parked on a gate, so the teardown sits queued while the build is assigned: that is the
        // race, and it is why a check made inline in reap() would not be enough to catch this.
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        reaps.submit(() -> {
            gate.await();
            return null;
        });
        try {
            strategy.reap(computer, reaps);
            idle.set(false); // the queue puts a build on the very spare the drain just picked

            gate.countDown();
            reaps.shutdown();
            assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "the queued teardown should have run");
            assertEquals(
                    0,
                    destroyCount(fake),
                    "a build that got in before the teardown must not lose its VM: " + fake.calls());
            assertTrue(r.jenkins.getNodes().contains(spare), "the agent running that build must still exist");
        } finally {
            gate.countDown();
            reaps.shutdownNow();
        }

        idle.set(true);
        ExecutorService later = Executors.newSingleThreadExecutor();
        try {
            strategy.reap(computer, later);

            later.shutdown();
            assertTrue(later.awaitTermination(30, TimeUnit.SECONDS), "the later teardown should finish");
            assertTrue(
                    fake.calls().contains("destroyWithDisks:" + spare.getVmRef()),
                    "abandoning a teardown must leave the agent reclaimable, not leak it: " + fake.calls());
        } finally {
            later.shutdownNow();
        }
    }

    // ---- The async teardown: guard, rejection, and error paths ----

    /**
     * Two reaps of the same agent must destroy its VM once, not once per call. The first reap holds the guard
     * while its teardown is queued behind a gate; the second must short-circuit rather than fire a duplicate
     * destroy at the same VM.
     */
    @Test
    void reapDestroysAtMostOnceWhileATeardownIsInFlight(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, agent.toComputer());

        // One worker, blocked on a gate, so the first reap's teardown queues and the guard stays held while
        // the second reap runs. Only when the gate opens does the single queued teardown execute.
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        reaps.submit(() -> {
            gate.await();
            return null;
        });
        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);
        try {
            strategy.reap(computer, reaps);
            strategy.reap(computer, reaps);

            gate.countDown();
            reaps.shutdown();
            assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "the teardown should finish");
            assertEquals(
                    1,
                    destroyCount(fake),
                    "the reaping guard must collapse two reaps into one destroy: " + fake.calls());
        } finally {
            gate.countDown();
            reaps.shutdownNow();
        }
    }

    /**
     * If the executor rejects the teardown submit (the remoting pool does this only while shutting down), the
     * guard must be cleared so a later reap can retry rather than pinning the node non-accepting with its VM
     * leaked. A first reap onto a dead executor destroys nothing; a second onto a live one then reaps.
     */
    @Test
    void aRejectedReapClearsTheGuardSoALaterReapRetries(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, agent.toComputer());
        XcpngRetentionStrategy strategy = new XcpngRetentionStrategy(IDLE_MINUTES);

        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdownNow();
        strategy.reap(computer, dead);
        assertEquals(0, destroyCount(fake), "a rejected teardown submit destroys nothing");
        assertFalse(r.jenkins.getNodes().isEmpty(), "a rejected reap must leave the node in place, not orphan it");

        ExecutorService live = Executors.newSingleThreadExecutor();
        try {
            strategy.reap(computer, live);
            live.shutdown();
            assertTrue(live.awaitTermination(30, TimeUnit.SECONDS), "the retry teardown should finish");
            assertTrue(
                    fake.calls().contains("destroyWithDisks:" + agent.getVmRef()),
                    "the guard must have been cleared so a later reap can retry: " + fake.calls());
        } finally {
            live.shutdownNow();
        }
    }

    /**
     * A destroy that throws must not derail node removal: _terminate swallows the failure and the node still
     * goes away, so a transient pool outage during teardown cannot leave a zombie node behind.
     */
    @Test
    void terminateRemovesTheNodeEvenWhenDestroyThrows(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").failDestroy();
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);

        assertDoesNotThrow(agent::terminate, "a failing destroy must not propagate out of teardown");

        assertTrue(
                fake.calls().contains("destroyWithDisks:" + agent.getVmRef()),
                "the destroy must have been attempted: " + fake.calls());
        assertFalse(r.jenkins.getNodes().contains(agent), "the node must be removed even though the destroy threw");
    }

    /**
     * A destroy that throws must record the VM as leaked on its cloud, so the warm-pool maintainer's sweep can
     * reclaim it later. Without this the node is still removed (above) but the VM ref is lost for good, since
     * nothing in Jenkins references it once the node is gone, and the VM leaks on the pool forever -- the #27
     * bug. Dropping the recordLeakedVm call leaves this red while the removal test above still passes.
     */
    @Test
    void terminateRecordsTheLeakedVmWhenDestroyThrows(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").failDestroy();
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);

        agent.terminate();

        assertTrue(
                cloud.leakedVmRefs().contains(agent.getVmRef()),
                "a failed destroy must record the VM as leaked for later reclamation: " + cloud.leakedVmRefs());
    }

    /**
     * An agent whose cloud has been deleted from the configuration must terminate cleanly: no client is
     * opened, no VM destroyed, the node still removed. Otherwise teardown would dereference a missing cloud.
     */
    @Test
    void terminateReturnsCleanlyWhenTheCloudIsGone(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1", false);
        r.jenkins.addNode(agent);

        // The administrator removes the cloud while the agent still runs; its cloudName now resolves to nothing.
        r.jenkins.clouds.remove(cloud);

        assertDoesNotThrow(agent::terminate, "terminating an agent whose cloud is gone must not throw");

        assertEquals(0, destroyCount(fake), "no VM can be destroyed once the cloud is gone: " + fake.calls());
        assertFalse(r.jenkins.getNodes().contains(agent), "the node must still be removed");
    }
}
