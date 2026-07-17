package io.jenkins.plugins.xcpng;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.VmRef;
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

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        if (!(c instanceof XcpngComputer) || !(c.getNode() instanceof XcpngAgent agent)) {
            return;
        }
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
}
