package io.jenkins.plugins.xcpng;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reclaims an XCP-ng agent after its first build, or after it sits idle past a timeout, whichever
 * comes first.
 *
 * <p>Single-use is the point: a clone runs one build on a pristine VM, then the VM and its disks are
 * destroyed. The idle timeout is only the safety net for a clone that connected but never received
 * work.
 *
 * <p>Both paths reap on a background thread rather than inline. Destroying the VM is a blocking network
 * call, and the superclass's idle teardown would otherwise run it on the periodic retention thread;
 * doing it asynchronously keeps that thread responsive and the build's own thread free to finalise.
 */
public class XcpngRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(XcpngRetentionStrategy.class.getName());

    private final int idleMinutes;

    /**
     * Guards against a second reap while the first is already in flight. Cleared when the reap task
     * finishes (so a failed teardown can be retried) and reset to false on deserialization.
     */
    private transient boolean reaping;

    /**
     * Executor the idle net and a build's completion reap on. Null in production, where {@link #reapExecutor()}
     * falls back to {@code Computer.threadPoolForRemoting}; a test injects a controllable one so the async
     * teardown is observable by the time it asserts. Transient: behaviour, never persisted.
     */
    private transient ExecutorService reapExecutor;

    /**
     * Clock for the idle-timeout math. Null in production, where {@link #now()} reads the system clock; a
     * test injects a fixed instant so {@link #check} can be driven just before or past the timeout without a
     * minutes-long wait, while the real {@code MINUTES.toMillis} conversion stays under test. Transient.
     */
    private transient LongSupplier clock;

    public XcpngRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
        this.idleMinutes = idleMinutes;
    }

    /**
     * Reclaim on idle timeout without blocking the periodic retention thread on VM teardown. Replaces
     * the superclass's synchronous teardown: when the computer has been idle longer than the timeout,
     * trigger an asynchronous reap and return promptly.
     */
    @Override
    @SuppressWarnings("rawtypes") // Match CloudRetentionStrategy.check's raw parameter to override it.
    public long check(AbstractCloudComputer computer) {
        // A warm-pool spare that connected but has not yet run a build is kept hot for the queue rather
        // than idle-reaped: that is the whole point of the warm pool. The exemption is deliberately
        // narrow (see exemptFromIdleReap), so an offline spare that never connected, or one whose cloud
        // was deleted, still falls through to the idle net below and can never leak its VM.
        if (isExemptWarmSpare(computer)) {
            return 1;
        }
        // Otherwise, deliberately not gated on isOnline(): a clone that started but never connected (bad
        // boot, network, or JNLP misconfiguration) is offline forever, and gating on online would leave
        // its VM to leak. Reaping an offline-and-idle computer past the timeout is exactly what the safety
        // net is for, and matches the superclass CloudRetentionStrategy.
        if (idleMinutes > 0 && computer.isIdle()) {
            long idleMillis = now() - computer.getIdleStartMilliseconds();
            if (idleMillis > TimeUnit.MINUTES.toMillis(idleMinutes)) {
                reap(computer);
            }
        }
        return 1;
    }

    /** The current instant in millis, from the injected clock in tests or the system clock in production. */
    private long now() {
        return clock != null ? clock.getAsLong() : System.currentTimeMillis();
    }

    /** Whether the computer backs an unused warm spare that should be kept hot rather than idle-reaped. */
    private static boolean isExemptWarmSpare(AbstractCloudComputer<?> computer) {
        return computer.getNode() instanceof XcpngAgent agent
                && exemptFromIdleReap(agent.isWarm(), computer.isOnline(), agent.getCloud() != null);
    }

    /**
     * Pure exemption rule, split out so its input combinations are unit-testable without a live computer.
     * A warm spare is kept only while it is both online (an offline one that never connected must still be
     * reaped so its VM does not leak) and still attached to a live cloud (a spare whose cloud was deleted
     * must lose the exemption, or the idle net could never reclaim it).
     */
    static boolean exemptFromIdleReap(boolean warm, boolean online, boolean cloudPresent) {
        return warm && online && cloudPresent;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        // The spare has work now: drop the warm exemption so it reverts to ordinary single-use behaviour
        // (reclaimed by taskCompleted below, or by the idle net if the build never completes). A build is
        // already running by the time this fires, so the computer is not idle and check() would not reap
        // it anyway; this simply keeps the exemption predicate honest for any later idle moment.
        if (executor.getOwner().getNode() instanceof XcpngAgent agent) {
            agent.markUsed();
        }
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        reapOwner(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        reapOwner(executor);
    }

    private void reapOwner(Executor executor) {
        if (executor.getOwner() instanceof AbstractCloudComputer<?> computer) {
            reap(computer);
        }
    }

    /** Reap on the remoting pool, the production path for the idle net and a build's completion. */
    private void reap(AbstractCloudComputer<?> computer) {
        reap(computer, reapExecutor());
    }

    /** The executor the idle net and task-completion reap on: an injected one in tests, the remoting pool otherwise. */
    private ExecutorService reapExecutor() {
        return reapExecutor != null ? reapExecutor : Computer.threadPoolForRemoting;
    }

    /** Test seam: run the idle-net and task-completion reaps on a controllable executor. */
    void setReapExecutor(ExecutorService reapExecutor) {
        this.reapExecutor = reapExecutor;
    }

    /** Test seam: drive the idle-timeout math off a fixed clock instead of the wall clock. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Reclaim this agent asynchronously, at most once at a time. Package-visible, and with the executor a
     * parameter, so {@link XcpngCloud#reconcileWarmPool} can drain a surplus warm spare through this very
     * method rather than reimplementing it.
     *
     * <p>Sharing the path is what makes the {@link #reaping} guard meaningful. A warm spare that never came
     * online is not exempt (see {@link #exemptFromIdleReap}), so the idle net below and a warm-pool drain can
     * both decide to reclaim the same spare at the same moment; routing both through this monitor means the
     * loser returns instead of firing a second {@code destroyWithDisks} at a VM the winner is already
     * destroying. The guard is per-agent, since each {@link XcpngAgent} holds its own strategy instance.
     */
    synchronized void reap(AbstractCloudComputer<?> computer, ExecutorService executor) {
        if (reaping) {
            return;
        }
        if (!(computer.getNode() instanceof XcpngAgent agent)) {
            return;
        }
        reaping = true;
        // Refuse further work immediately so nothing new is scheduled onto a node about to die.
        computer.setAcceptingTasks(false);
        LOGGER.log(Level.FINE, () -> "Reclaiming XCP-ng agent " + agent.getNodeName());
        try {
            executor.submit(() -> {
                try {
                    agent.terminate();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> "Failed to terminate agent " + agent.getNodeName());
                } finally {
                    // Clear the guard whatever happened. On success the node is already gone, so the next
                    // reap short-circuits on the getNode() check; on a transient failure this lets a later
                    // periodic check retry the teardown rather than leaving the VM to leak forever.
                    synchronized (this) {
                        reaping = false;
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // The remoting pool would not even accept the teardown task, which it does only while
            // shutting down. Clear the guard here (the task's finally will never run) so a later check
            // can retry rather than pinning the node non-accepting with its VM leaked.
            LOGGER.log(Level.WARNING, e, () -> "Could not schedule reclamation of agent " + agent.getNodeName());
            reaping = false;
        }
    }
}
