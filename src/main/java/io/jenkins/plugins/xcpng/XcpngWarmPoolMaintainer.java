package io.jenkins.plugins.xcpng;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Keeps every {@link XcpngCloud}'s warm pools topped up. Runs about once a minute off the Jenkins timer
 * and asks each cloud to reconcile: launch pre-booted spares up to each template's {@code minInstances}, so
 * a queued build lands on a ready executor instead of waiting for a cold clone.
 *
 * <p>Demand-driven provisioning cannot do this: {@link XcpngCloud#provision} is only called once work is
 * already queued. Pre-provisioning therefore needs a periodic maintainer, which is the idiomatic shape for
 * cloud plugins that keep a warm pool.
 */
@Extension
public class XcpngWarmPoolMaintainer extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(XcpngWarmPoolMaintainer.class.getName());

    /** How long after startup the first reconcile runs. See {@link #getInitialDelay()}. */
    private static final long INITIAL_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(10);

    public XcpngWarmPoolMaintainer() {
        super("XCP-ng warm pool maintainer");
    }

    /** Reconcile roughly once a minute; each pass is cheap bookkeeping plus non-blocking clone submits. */
    @Override
    public long getRecurrencePeriod() {
        return MIN;
    }

    /**
     * Run the first reconcile promptly instead of at a random point in the first period.
     *
     * <p>{@link hudson.model.PeriodicWork#getInitialDelay()} returns
     * {@code Math.abs(RANDOM.nextLong()) % getRecurrencePeriod()}, so with a one-minute period the first
     * pass lands anywhere in a 60 second window. That is the right default for the many periodic tasks
     * whose only requirement is not to start in lockstep with each other, and it is the wrong one here.
     * A controller restart reclaims every warm spare it had — {@link XcpngAgent#readResolve} cannot see a
     * transient {@code warm} flag, so a reloaded spare is reaped rather than adopted — and until this
     * maintainer's first pass runs, nothing replaces them. The pool therefore holds no spare for however
     * long the random draw happened to be, on top of the clone-and-boot that is real work.
     *
     * <p>A fixed short delay is safe here specifically because of when core schedules periodic work.
     * {@code PeriodicWork.init()} carries {@code @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)},
     * while nodes are loaded by the "Loading global config" task, which attains
     * {@code SYSTEM_CONFIG_LOADED} — three milestones earlier. The node list this reconcile reads is
     * therefore fully populated before the maintainer is scheduled at all, so bringing the first pass
     * forward cannot make it read a deficit that is not real and clone against it. (Verified against
     * jenkins-core 2.555.3 with {@code javap}; both facts are load-bearing, so re-check them rather than
     * assume if this is ever revisited on a newer core.)
     *
     * <p>The delay is short rather than zero so that a restart's own churn — agents reconnecting, the
     * reclaim of the previous spares — has settled before the deficit is counted. It costs a few seconds
     * of a gap that was up to a minute.
     */
    @Override
    public long getInitialDelay() {
        return INITIAL_DELAY_MILLIS;
    }

    @Override
    protected void execute(TaskListener listener) {
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof XcpngCloud xcpng) {
                try {
                    xcpng.reconcileWarmPool();
                } catch (RuntimeException e) {
                    // One cloud's failure must not stop the others being reconciled this tick.
                    LOGGER.log(Level.WARNING, e, () -> "Warm-pool reconcile failed for cloud " + xcpng.name);
                }
                try {
                    // Reissue any teardown that failed and left a VM recorded as leaked. Separate try so a
                    // sweep failure does not skip the next cloud, and so a reconcile failure above does not
                    // skip this cloud's sweep.
                    xcpng.sweepLeakedVms();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, e, () -> "Leaked-VM sweep failed for cloud " + xcpng.name);
                }
            }
        }
    }
}
