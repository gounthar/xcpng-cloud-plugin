package io.jenkins.plugins.xcpng;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.VmRef;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scrubs the per-clone JNLP secret from a VM's guest-data record as soon as its agent connects.
 *
 * <p>The secret has to be seeded into the VM record for the guest to read it at boot (see
 * {@link io.jenkins.plugins.xcpng.client.XapiClient#clearGuestSecret}), where it is readable by any pool
 * session with read access to VM objects. Nothing consumes it after the guest has read it once and
 * connected, so removing it here shrinks its exposure from the whole build down to the
 * boot-until-connect window. A pool-side reader can still impersonate the agent during that window; that
 * residual is inherent to delivering a seed through the VM record and is documented in the cloud help
 * and README security notes.
 *
 * <p>Failures are logged, never thrown: a computer that has already connected must stay online even if
 * the scrub could not run, and a later idle-reap or the build's completion tears the VM down regardless.
 */
@Extension
public class XcpngComputerListener extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(XcpngComputerListener.class.getName());

    /**
     * Executor the scrub runs on. Null in production, where {@link #scrubExecutor()} falls back to
     * {@code Computer.threadPoolForRemoting}; a test injects a controllable one so it can observe that
     * {@link #onOnline} returned before the scrub ran. Transient: behaviour, never persisted.
     */
    private transient ExecutorService scrubExecutor;

    /**
     * Hand the scrub to a worker and return, so the pool is never on the connecting thread's critical path.
     *
     * <p>{@code onOnline} runs inside the computer's connection sequence, and the scrub is an optimization
     * of exposure rather than a prerequisite for the agent working — the guest has already read the seed by
     * the time this fires. Doing the XAPI calls here would pin the connecting thread for the transport
     * timeouts whenever the pool is slow or unreachable, which is likeliest exactly when several clones are
     * connecting at once, and that delay eats into the online wait the provisioning task is counting down.
     */
    @Override
    public void onOnline(Computer c, TaskListener listener) {
        if (!(c instanceof XcpngComputer) || !(c.getNode() instanceof XcpngAgent agent)) {
            return;
        }
        try {
            scrubExecutor().execute(() -> scrub(agent));
        } catch (RejectedExecutionException e) {
            // The remoting pool is a cached one, so it only rejects once shut down: Jenkins is going down
            // and the VM is about to stop mattering. Logged rather than thrown, because the connection
            // sequence must not carry a failure from an optimization it does not depend on -- the same
            // reason scrub() swallows its own. Core would catch this and leave the agent online anyway;
            // the point is that this class promises never to throw.
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "Could not schedule the seed-secret scrub for agent " + agent.getNodeName() + " (VM "
                            + agent.getVmRef() + ")");
        }
    }

    /**
     * Remove the seed secret from the agent's VM record. Ordering against anything else is irrelevant:
     * {@code VM.remove_from_xenstore_data} is idempotent and nothing else touches that key.
     */
    private static void scrub(XcpngAgent agent) {
        // Re-read the cloud here rather than in onOnline: it may have been deleted or renamed between the
        // agent coming online and this task reaching a worker.
        XcpngCloud cloud = agent.getCloud();
        if (cloud == null) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "Cloud '" + agent.getCloudName() + "' is gone; cannot scrub the seed secret for agent "
                            + agent.getNodeName());
            return;
        }
        try (HypervisorClient client = cloud.openClient()) {
            client.clearGuestSecret(new VmRef(agent.getVmRef()));
            LOGGER.log(Level.FINE, () -> "Scrubbed the seed secret for agent " + agent.getNodeName());
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "Failed to scrub the seed secret for agent " + agent.getNodeName() + " (VM "
                            + agent.getVmRef() + "); it remains in the VM record until teardown");
        }
    }

    /** The executor the scrub runs on: an injected one in tests, the remoting pool otherwise. */
    private ExecutorService scrubExecutor() {
        return scrubExecutor != null ? scrubExecutor : Computer.threadPoolForRemoting;
    }

    /** Test seam: run the scrub on a controllable executor instead of the remoting pool. */
    void setScrubExecutor(ExecutorService scrubExecutor) {
        this.scrubExecutor = scrubExecutor;
    }
}
