package io.jenkins.plugins.xcpng;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reclaims an XCP-ng agent after its first build, or after it sits idle past a timeout, whichever
 * comes first.
 *
 * <p>Single-use is the point: a clone runs one build on a pristine VM, then the VM and its disks are
 * destroyed. The idle timeout, inherited from {@link CloudRetentionStrategy}, is only the safety net
 * for a clone that connected but never received work.
 */
public class XcpngRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(XcpngRetentionStrategy.class.getName());

    public XcpngRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        // Nothing to do on accept; reclamation happens once the one build finishes.
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        terminateOwner(executor);
    }

    @Override
    public void taskCompletedWithProblems(
            Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        terminateOwner(executor);
    }

    private void terminateOwner(Executor executor) {
        if (!(executor.getOwner() instanceof AbstractCloudComputer<?> computer)) {
            return;
        }
        // Refuse further work immediately so nothing new is scheduled onto a node about to die.
        computer.setAcceptingTasks(false);
        final XcpngAgent agent = (XcpngAgent) computer.getNode();
        if (agent == null) {
            return;
        }
        LOGGER.log(Level.FINE, () -> "Single-use agent " + agent.getNodeName() + " finished its build; terminating");
        // Terminate off the executor thread so the build can finalise before the VM disappears.
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
