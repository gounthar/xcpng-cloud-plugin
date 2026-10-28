package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.model.Node;
import hudson.slaves.Cloud;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorException;
import io.jenkins.plugins.xcpng.client.VmRef;
import io.jenkins.plugins.xcpng.client.XapiEraVm;
import io.jenkins.plugins.xcpng.client.XapiEraVms;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * Forget button, for once the VM has been reclaimed with {@code tools/reaper.py}, and a Reclaim button (#364) that
 * finds the VM through the cloud's Xen Orchestra connection, shows what it found, and on confirmation takes it over
 * and hands it to the held-entry sweep. Never automatic: an undrained agent's VM can hold the only copy of a failed
 * build's workspace, and nothing here acts before an administrator has looked.
 *
 * <p>Computed on each read rather than cached: a reconfigure replaces the cloud object, and an agent leaves
 * the node list when it terminates, so there is no event worth listening for that is cheaper than looking.
 */
@Extension
@Symbol("xcpngRemovedBackend")
public class XcpngRemovedBackendMonitor extends AdministrativeMonitor {

    private static final Logger LOGGER = Logger.getLogger(XcpngRemovedBackendMonitor.class.getName());

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

    /**
     * Look the entry's VM up through the cloud's Xen Orchestra connection and show what was found, with every check
     * that keeps it from being reclaimed. Changes nothing: the reclaim itself is {@link #doReclaimConfirm}.
     */
    @RequirePOST
    public HttpResponse doReclaim(@QueryParameter String cloud, @QueryParameter String vmRef) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return HttpResponses.forwardToView(this, "reclaim").with("preview", preview(cloud, vmRef));
    }

    /**
     * Reclaim the entry previewed as the VM with {@code uuid}: look it up again and re-run every check, stamp this
     * cloud's markers on it, then replace the entry with a held one on the cloud's connection (#306). The sweep
     * destroys it from there, through {@code refuseUnlessOwned}, and only once the pool reads it in a state Xen
     * Orchestra shuts down before deleting (#48). Anything that does not hold shows the preview again instead.
     */
    @RequirePOST
    public HttpResponse doReclaimConfirm(
            @QueryParameter String cloud, @QueryParameter String vmRef, @QueryParameter String uuid) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ReclaimPreview preview = preview(cloud, vmRef);
        XcpngLeakedVm entry = preview.getEntry();
        XcpngCloud owner = preview.getXcpngCloud();
        XapiEraVm vm = preview.getVm();
        if (!preview.isReclaimable()
                || entry == null
                || owner == null
                || vm == null
                || uuid == null
                || !uuid.equals(vm.uuid())) {
            return HttpResponses.forwardToView(this, "reclaim").with("preview", preview);
        }
        VmRef adopted;
        try (HypervisorClient client = owner.openClient()) {
            adopted = ((XapiEraVms) client).adoptXapiEraVm(vm, cloud);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not reclaim leaked XCP-ng VM " + entry + " of cloud " + cloud);
            return HttpResponses.forwardToView(this, "reclaim")
                    .with("preview", preview.failed(Messages.XcpngRemovedBackendMonitor_ReclaimFailed(e.getMessage())));
        }
        // The held entry first, then the old one out: an interruption between leaves both listed, never neither.
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record(cloud, XcpngLeakedVm.of(adopted.value(), owner).asHeld().named(entry.getNodeName()));
        store.drop(cloud, List.of(entry));
        String user = Jenkins.getAuthentication2().getName();
        LOGGER.log(
                Level.INFO,
                () -> "Reclaimed leaked XCP-ng VM " + entry + " of cloud " + cloud + " as " + adopted.value()
                        + " at the request of " + user + "; the sweep destroys it once the pool reads it running");
        return HttpResponses.redirectViaContextPath("/manage");
    }

    @NonNull
    private ReclaimPreview preview(@CheckForNull String cloud, @CheckForNull String vmRef) {
        List<XcpngLeakedVm> leaked =
                cloud == null || vmRef == null ? List.of() : getLeaked().getOrDefault(cloud, List.of());
        XcpngLeakedVm entry = leaked.stream()
                .filter(vm -> vm.getVmRef().equals(vmRef))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return new ReclaimPreview(cloud, null, null, null, List.of(Messages.XcpngRemovedBackendMonitor_NoEntry()));
        }
        Cloud configured = Jenkins.get().clouds.getByName(cloud);
        if (!(configured instanceof XcpngCloud xcpng) || xcpng.isConfiguredForRemovedBackend()) {
            return new ReclaimPreview(
                    cloud, entry, null, null, List.of(Messages.XcpngRemovedBackendMonitor_NoXoCloud(cloud)));
        }
        Optional<XapiEraVm> found;
        try (HypervisorClient client = xcpng.openClient()) {
            if (!(client instanceof XapiEraVms lookup)) {
                return new ReclaimPreview(
                        cloud, entry, xcpng, null, List.of(Messages.XcpngRemovedBackendMonitor_NoXoCloud(cloud)));
            }
            found = lookup.lookUpXapiEraVm(entry.getVmRef());
        } catch (HypervisorException | IllegalStateException e) {
            return new ReclaimPreview(
                    cloud,
                    entry,
                    xcpng,
                    null,
                    List.of(Messages.XcpngRemovedBackendMonitor_LookupFailed(e.getMessage())));
        }
        return new ReclaimPreview(
                cloud, entry, xcpng, found.orElse(null), XcpngXapiEraReclaim.refusals(cloud, entry, found));
    }

    /** What {@code reclaim.jelly} shows: the entry, the VM found for it if any, and why it may not be reclaimed. */
    public static final class ReclaimPreview {

        private final String cloud;
        private final XcpngLeakedVm entry;
        private final XcpngCloud xcpngCloud;
        private final XapiEraVm vm;
        private final List<String> refusals;

        ReclaimPreview(
                @CheckForNull String cloud,
                @CheckForNull XcpngLeakedVm entry,
                @CheckForNull XcpngCloud xcpngCloud,
                @CheckForNull XapiEraVm vm,
                @NonNull List<String> refusals) {
            this.cloud = cloud;
            this.entry = entry;
            this.xcpngCloud = xcpngCloud;
            this.vm = vm;
            this.refusals = List.copyOf(refusals);
        }

        @NonNull
        ReclaimPreview failed(@NonNull String why) {
            List<String> more = new ArrayList<>(refusals);
            more.add(why);
            return new ReclaimPreview(cloud, entry, xcpngCloud, vm, more);
        }

        @CheckForNull
        public String getCloud() {
            return cloud;
        }

        @CheckForNull
        public XcpngLeakedVm getEntry() {
            return entry;
        }

        @CheckForNull
        XcpngCloud getXcpngCloud() {
            return xcpngCloud;
        }

        @CheckForNull
        public XapiEraVm getVm() {
            return vm;
        }

        @NonNull
        public List<String> getRefusals() {
            return refusals;
        }

        public boolean isReclaimable() {
            return entry != null && xcpngCloud != null && vm != null && refusals.isEmpty();
        }
    }
}
