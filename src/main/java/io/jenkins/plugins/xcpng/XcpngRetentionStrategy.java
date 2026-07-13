package io.jenkins.plugins.xcpng;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;
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

    /** Guards against a second reap while the first is already in flight. Reset on deserialization. */
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
        if (idleMinutes > 0 && computer.isOnline() && computer.isIdle()) {
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
        Computer.threadPoolForRemoting.submit(() -> {
            try {
                agent.terminate();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, e, () -> "Failed to terminate agent " + agent.getNodeName());
            }
        });
    }
}
