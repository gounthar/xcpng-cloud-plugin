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
import java.util.function.Predicate;
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
     * finishes, which only releases the per-agent lock: the node is gone by then (see {@link #reap}), so
     * the clear is not a retry hook. Reset to false on deserialization.
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

    /**
     * Reads whether a computer is free of work. Null in production, where {@link #idle} asks the computer
     * itself; a test injects one because {@code Computer.isIdle()} is final and reads private executor state,
     * and an agent that never really connects can never run a build to drive it false. Transient: behaviour,
     * never persisted.
     */
    private transient Predicate<AbstractCloudComputer<?>> idleProbe;

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
        // An agent that outlived a controller restart is reclaimed on the first check rather than left to
        // age out. A restart erases both pieces of state single-use depends on: XcpngAgent.warm is transient,
        // so a spare and a spent agent are indistinguishable afterwards, and SlaveComputer.acceptingTasks is
        // transient too and constructs true, so the refusal taskAccepted stamped on a used agent is gone as
        // well. Left alone, an agent whose build finished just as the controller died comes back accepting
        // work on a VM that is no longer pristine, and a genuine spare holds a second slot against
        // maxInstances for the whole idle timeout while the warm pool clones its replacement.
        //
        // Going through reap() rather than terminating here is what makes this safe during startup, when the
        // restored queue is dispatching: reap re-reads the computer under the queue lock and abandons the
        // teardown if a build got in first (see idleUnderQueueLock). Such a build keeps its executor and the
        // completion reap reclaims the agent when it finishes.
        //
        // The two halves are deliberately split. Refusing work is unconditional, because that is the half
        // that closes the dirty-VM window and it must not wait for the agent to fall idle. Scheduling the
        // teardown is gated on the agent being idle, because otherwise every retention tick would queue a
        // reap that can only bail out under the queue lock: on a long build that is one pointless task and
        // one INFO line per minute, for hours. The completion reap covers the busy case, and if it somehow
        // does not fire, the agent falls idle and the next check picks it up here.
        if (isReloaded(computer)) {
            computer.setAcceptingTasks(false);
            if (idle(computer)) {
                reap(computer);
            }
            return 1;
        }
        // Otherwise, deliberately not gated on isOnline(): a clone that started but never connected (bad
        // boot, network, or JNLP misconfiguration) is offline forever, and gating on online would leave
        // its VM to leak. Reaping an offline-and-idle computer past the timeout is exactly what the safety
        // net is for, and matches the superclass CloudRetentionStrategy.
        if (idleMinutes > 0 && idle(computer)) {
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

    /** Whether the computer has no work in hand, from the injected probe in tests or the computer itself. */
    private boolean idle(AbstractCloudComputer<?> computer) {
        return idleProbe != null ? idleProbe.test(computer) : computer.isIdle();
    }

    /** Whether the computer backs an unused warm spare that should be kept hot rather than idle-reaped. */
    private static boolean isExemptWarmSpare(AbstractCloudComputer<?> computer) {
        return computer.getNode() instanceof XcpngAgent agent
                && exemptFromIdleReap(agent.isWarm(), computer.isOnline(), agent.getCloud() != null);
    }

    /** Whether the computer backs an agent that came back from a controller restart. */
    private static boolean isReloaded(AbstractCloudComputer<?> computer) {
        return computer.getNode() instanceof XcpngAgent agent && agent.isReloaded();
    }

    /**
     * Pure exemption rule, split out so its input combinations are unit-testable without a live computer.
     * A warm spare is kept only while it is both online (an offline one that never connected must still be
     * reaped so its VM does not leak) and still attached to a live cloud: a spare whose cloud was deleted
     * belongs to no pool the plugin still provisions against, so keeping it hot would hold a VM for a queue
     * that can never route work to it. The idle net then reclaims both halves — the node, and (through the
     * agent's connection snapshot, see {@link XcpngAgent#_terminate}) the VM behind it.
     */
    static boolean exemptFromIdleReap(boolean warm, boolean online, boolean cloudPresent) {
        return warm && online && cloudPresent;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        if (!(executor.getOwner() instanceof AbstractCloudComputer<?> computer)) {
            return;
        }
        // This agent has its one build now, so refuse any further assignment from this moment rather than
        // waiting for the completion reap to do it. Without this the computer stays assignable in the window
        // between the build finishing and the reap taking effect, and a second build landing there would both
        // run on a no-longer-pristine VM and be killed by the teardown already under way.
        computer.setAcceptingTasks(false);
        // The spare has work now: drop the warm exemption so it reverts to ordinary single-use behaviour
        // (reclaimed by taskCompleted below, or by the idle net if the build never completes). A build is
        // already running by the time this fires, so the computer is not idle and check() would not reap
        // it anyway; this simply keeps the exemption predicate honest for any later idle moment.
        if (computer.getNode() instanceof XcpngAgent agent) {
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

    /**
     * Reap the agent whose build just finished. The idle re-check is deliberately skipped here: the executor
     * still holds the finished executable while its listeners run, so the computer reads busy and the very
     * reap that makes single-use work would abandon itself every time.
     */
    private void reapOwner(Executor executor) {
        if (executor.getOwner() instanceof AbstractCloudComputer<?> computer) {
            reap(computer, reapExecutor(), false);
        }
    }

    /** Reap on the remoting pool, the production path for the idle net. */
    private void reap(AbstractCloudComputer<?> computer) {
        reap(computer, reapExecutor(), true);
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
     * Test seam: report a computer busy without a real build. {@code Computer.isIdle()} is final and reads
     * private executor state, and these agents never really connect, so there is no other way to drive the
     * accept-versus-reap race.
     */
    void setIdleProbe(Predicate<AbstractCloudComputer<?>> idleProbe) {
        this.idleProbe = idleProbe;
    }

    /**
     * Reclaim this agent asynchronously, at most once at a time. Package-visible, and with the executor a
     * parameter, so {@link XcpngCloud#reconcileWarmPool} can drain a surplus warm spare through this very
     * method rather than reimplementing it.
     *
     * <p>Both of those callers decide to reclaim an agent by reading {@code isIdle()}, and the destroy lands
     * some time after that read. Refusing further tasks and re-reading the computer under the queue lock
     * before the teardown (see {@link #idleUnderQueueLock}) is what stops a build assigned inside that window
     * from losing its VM mid-run.
     *
     * <p>Sharing the path is what makes the {@link #reaping} guard meaningful. A warm spare that never came
     * online is not exempt (see {@link #exemptFromIdleReap}), so the idle net below and a warm-pool drain can
     * both decide to reclaim the same spare at the same moment; routing both through this monitor means the
     * loser returns instead of firing a second {@code destroyWithDisks} at a VM the winner is already
     * destroying. The guard is per-agent, since each {@link XcpngAgent} holds its own strategy instance.
     */
    synchronized void reap(AbstractCloudComputer<?> computer, ExecutorService executor) {
        reap(computer, executor, true);
    }

    /**
     * @param requireIdle whether to abandon the teardown if the computer turns out to have work in hand.
     *     True for the idle net and the warm-pool drain: both decided to reclaim this agent while it was
     *     idle, and a build assigned in between must not be killed by the destroy. False for the completion
     *     path, where the finished build still counts as work in hand (see {@link #reapOwner}).
     */
    private synchronized void reap(AbstractCloudComputer<?> computer, ExecutorService executor, boolean requireIdle) {
        if (reaping) {
            return;
        }
        if (!(computer.getNode() instanceof XcpngAgent agent)) {
            return;
        }
        reaping = true;
        // Refuse further work immediately so nothing new is scheduled onto a node about to die. This is
        // published before the re-check below reads the computer back, which is what makes that read
        // meaningful rather than another sample of a moving target.
        computer.setAcceptingTasks(false);
        LOGGER.log(Level.FINE, () -> "Reclaiming XCP-ng agent " + agent.getNodeName());
        try {
            executor.submit(() -> {
                try {
                    if (requireIdle && !idleUnderQueueLock(computer)) {
                        // A build was assigned between the caller's idle check and here. Destroying the VM
                        // now would kill it with a channel loss and no explanation, so leave the agent alone
                        // and let the completion reap reclaim it when the build finishes. The node stays
                        // non-accepting: it is a used, single-use agent from this point either way.
                        LOGGER.log(
                                Level.INFO,
                                () -> "Not reclaiming XCP-ng agent " + agent.getNodeName()
                                        + ": it took on work after the reclamation was decided");
                        return;
                    }
                    agent.terminate();
                    // The node is gone now -- AbstractCloudSlave.terminate removes it in its own finally -- so
                    // the slot it held against maxInstances is free and a warm pool may have a deficit it could
                    // not fill a moment ago. Ask for a reconcile at this moment rather than leaving it to the
                    // maintainer's next tick, which lands anywhere in a one-minute period (#120).
                    //
                    // Placement is load-bearing. Here, in the success path, rather than in the finally below:
                    // the requireIdle branch above abandons the teardown with the node still registered and no
                    // slot freed, and a reconcile fired for that would be asking about capacity that never
                    // came back. A terminate that throws is the one case this misses; the node is removed
                    // regardless, so that freed slot waits for the maintainer, and the VM behind it is already
                    // recorded as leaked for the sweep.
                    //
                    // requestReconcile only raises a flag and submits, never taking the cloud monitor on this
                    // thread. That is what keeps this call clear of the lock order the plugin already has:
                    // reconcileWarmPool holds the cloud monitor while drainSpare calls into this class's, so a
                    // trigger that reconciled inline while holding a strategy monitor would take the two the
                    // other way round, and a concurrent drain makes that a real deadlock rather than a
                    // theoretical one.
                    XcpngCloud cloud = agent.getCloud();
                    if (cloud != null) {
                        cloud.requestReconcile();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, e, () -> "Failed to terminate agent " + agent.getNodeName());
                } finally {
                    // Clear the guard whatever happened; this only releases the per-agent lock, it is not a
                    // teardown retry. AbstractCloudSlave.terminate removes the node in its own finally whether
                    // or not _terminate's destroy threw, so by the time this runs getNode() is null and the
                    // next check() short-circuits (XcpngRetentionStrategy.check line ~78) -- nothing here ever
                    // reissues the destroy. A destroy that did throw is instead recorded as a leaked VM on the
                    // cloud (XcpngAgent._terminate -> XcpngCloud.recordLeakedVm) and re-destroyed by
                    // XcpngWarmPoolMaintainer on a later tick, which is the only path that actually retries.
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

    /**
     * Whether the computer has no work in hand, read while holding the queue lock so the answer cannot be
     * invalidated by an assignment already in flight. {@code setAcceptingTasks(false)} has been published by
     * the time this runs, so a scheduling pass either finished before the lock was taken, in which case its
     * assignment is visible here, or starts after it is released and finds the node refusing work. Read
     * without the lock, the window would stay open: the queue samples {@code isAcceptingTasks} at the start
     * of a pass and assigns at the end of it, so a pass already under way can still place a build on a node
     * that has just stopped accepting.
     *
     * <p>Only the read is under the lock. The teardown itself must not be: {@code destroyWithDisks} is a
     * blocking network call, and holding the queue lock across it would stall scheduling for the whole
     * controller for as long as the pool takes to answer.
     */
    private boolean idleUnderQueueLock(AbstractCloudComputer<?> computer) {
        return Queue.callWithLock(() -> idle(computer));
    }
}
