package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.model.Node;
import hudson.slaves.Cloud;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Tells administrators which clouds and agents are still configured for the removed XAPI backend (#89).
 *
 * <p>Such a cloud loads with its configuration intact but provisions nothing, and such an agent's VM cannot be
 * destroyed by this plugin any more. Neither failure is loud on its own: a cloud that declines to provision
 * shows up only as a build waiting in the queue, and a teardown that cannot open a client shows up only in
 * the log. This monitor is what makes the state visible where an administrator looks, and it clears itself
 * once every such cloud has been saved against Xen Orchestra and every such agent is gone.
 *
 * <p>It also lists the leaked VMs recorded over the removed backend (#330): an agent torn down while it could
 * not be destroyed, typically one still running at the upgrade. The sweep keeps those entries rather than giving
 * up on them, because no sweep can reach them and this list is then the only thing naming them. Each has a
 * Forget button, for once the VM has been reclaimed with {@code tools/reaper.py}.
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
        return !getClouds().isEmpty()
                || !getAgentNames().isEmpty()
                || !getLeaked().isEmpty();
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

    /** Leaked entries recorded over the removed backend, by cloud name. The view prints each with a Forget button. */
    @NonNull
    public Map<String, List<XcpngLeakedVm>> getLeaked() {
        return XcpngLeakedVmStore.get().onRemovedBackend();
    }

    /**
     * Stop tracking the removed-backend entries of {@code cloud} whose ref is {@code vmRef}. Only those: an entry
     * on a supported backend is the sweep's, or the held-leak banner's, to settle. A ref that no longer matches
     * changes nothing and lands on the same page.
     */
    @RequirePOST
    public HttpResponse doForget(@QueryParameter String cloud, @QueryParameter String vmRef) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        // getLeaked() is a TreeMap, which throws on a null key: a POST without the field would otherwise be a 500.
        List<XcpngLeakedVm> leaked =
                cloud == null || vmRef == null ? List.of() : getLeaked().getOrDefault(cloud, List.of());
        XcpngLeakedVmStore.get()
                .forget(
                        cloud,
                        leaked.stream()
                                .filter(vm -> vm.getVmRef().equals(vmRef))
                                .toList());
        return HttpResponses.redirectViaContextPath("/manage");
    }
}
