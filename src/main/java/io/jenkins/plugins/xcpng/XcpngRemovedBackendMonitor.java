package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.model.Node;
import hudson.slaves.Cloud;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;

/**
 * Tells administrators which clouds and agents are still configured for the removed XAPI backend (#89).
 *
 * <p>Such a cloud loads with its configuration intact but provisions nothing, and such an agent's VM cannot be
 * destroyed by this plugin any more. Neither failure is loud on its own: a cloud that declines to provision
 * shows up only as a build waiting in the queue, and a teardown that cannot open a client shows up only in
 * the log. This monitor is what makes the state visible where an administrator looks, and it clears itself
 * once every such cloud has been saved against Xen Orchestra and every such agent is gone.
 *
 * <p>Computed on each read rather than cached: a reconfigure replaces the cloud object, and an agent leaves
 * the node list when it terminates, so there is no event worth listening for that is cheaper than looking.
 */
@Extension
@Symbol("xcpngRemovedBackend")
public class XcpngRemovedBackendMonitor extends AdministrativeMonitor {

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.XcpngRemovedBackendMonitor_DisplayName();
    }

    @Override
    public boolean isActivated() {
        return !getClouds().isEmpty() || !getAgentNames().isEmpty();
    }

    /**
     * The clouds still set up for the removed backend, including one that names XO but still carries a
     * username/password credential (see {@link XcpngCloud#isConfiguredForRemovedBackend()}). The view links
     * each to its configuration page.
     */
    @NonNull
    public List<XcpngCloud> getClouds() {
        List<XcpngCloud> clouds = new ArrayList<>();
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof XcpngCloud xcpng && xcpng.isConfiguredForRemovedBackend()) {
                clouds.add(xcpng);
            }
        }
        return clouds;
    }

    /**
     * Names of the agents whose VM was provisioned over the removed backend. Read from each agent's own
     * connection snapshot rather than from its cloud, because that snapshot is what its teardown uses: an
     * agent provisioned over XAPI stays unreachable even after its cloud has been moved to Xen Orchestra.
     */
    @NonNull
    public List<String> getAgentNames() {
        List<String> names = new ArrayList<>();
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof XcpngAgent agent && !agent.getBackend().isSupported()) {
                names.add(agent.getNodeName());
            }
        }
        return names;
    }
}
