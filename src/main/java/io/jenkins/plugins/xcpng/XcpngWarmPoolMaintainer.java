package io.jenkins.plugins.xcpng;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
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

    public XcpngWarmPoolMaintainer() {
        super("XCP-ng warm pool maintainer");
    }

    /** Reconcile roughly once a minute; each pass is cheap bookkeeping plus non-blocking clone submits. */
    @Override
    public long getRecurrencePeriod() {
        return MIN;
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
            }
        }
    }
}
