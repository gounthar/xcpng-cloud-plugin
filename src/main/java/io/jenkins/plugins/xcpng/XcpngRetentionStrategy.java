package io.jenkins.plugins.xcpng;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
        // Deliberately not gated on isOnline(): a clone that started but never connected (bad boot,
        // network, or JNLP misconfiguration) is offline forever, and gating on online would leave its
        // VM to leak. Reaping an offline-and-idle computer past the timeout is exactly what the safety
        // net is for, and matches the superclass CloudRetentionStrategy.
        if (idleMinutes > 0 && computer.isIdle()) {
            long idleMillis = System.currentTimeMillis() - computer.getIdleStartMilliseconds();
            if (idleMillis > TimeUnit.MINUTES.toMillis(idleMinutes)) {
                reap(computer);
            }
        }
        return 1;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        // Nothing to do on accept; reclamation happens once the one build finishes.
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        reapOwner(executor);
    }

    @Override
    public void taskCompletedWithProblems(
            Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        reapOwner(executor);
    }

    private void reapOwner(Executor executor) {
        if (executor.getOwner() instanceof AbstractCloudComputer<?> computer) {
            reap(computer);
        }
    }

    private synchronized void reap(AbstractCloudComputer<?> computer) {
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
            Computer.threadPoolForRemoting.submit(() -> {
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
