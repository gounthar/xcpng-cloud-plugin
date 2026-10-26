package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Tells administrators which leaked VMs the sweep is holding back (#306).
 *
 * <p>A held VM is one whose teardown the #48 guard refused: the pool read it {@code Halted} while its agent was
 * still connected. The sweep destroys it only once the pool reads it running, and there is no age-out, so a VM
 * that really did stop stays recorded with its disks until someone looks. The sweep's SEVERE line says so once
 * per controller start; this is what keeps it visible where an administrator looks, across restarts, and it
 * clears itself as soon as the store holds no such entry.
 *
 * <p>Computed on each read from the store, like {@link XcpngRemovedBackendMonitor}: there is no event cheaper
 * to listen for than looking.
 *
 * <p>Each entry has a Forget button, for the one case the sweep cannot settle by itself: the VM was removed by
 * hand, or its cloud and credential were deleted, so nothing will ever read it running again. It is not core's
 * Dismiss, which hides the whole monitor, held VMs recorded later included.
 */
@Extension
@Symbol("xcpngHeldLeak")
public class XcpngHeldLeakMonitor extends AdministrativeMonitor {

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.XcpngHeldLeakMonitor_DisplayName();
    }

    @Override
    public boolean isActivated() {
        return !XcpngLeakedVmStore.get().held().isEmpty();
    }

    /** Held entries by cloud name. The view prints each VM and the reaper command for its cloud. */
    @NonNull
    public Map<String, List<XcpngLeakedVm>> getHeld() {
        return XcpngLeakedVmStore.get().held();
    }

    /**
     * Stop tracking the held entries of {@code cloud} whose ref is {@code vmRef}. Only held ones: an ordinary
     * entry is retried by the sweep and leaves on its own, so it is not this banner's to forget. A ref that no
     * longer matches, a second click say, changes nothing and lands on the same page.
     */
    @RequirePOST
    public HttpResponse doForget(@QueryParameter String cloud, @QueryParameter String vmRef) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        // getHeld() is a TreeMap, which throws on a null key: a POST without the field would otherwise be a 500.
        List<XcpngLeakedVm> held =
                cloud == null || vmRef == null ? List.of() : getHeld().getOrDefault(cloud, List.of());
        XcpngLeakedVmStore.get()
                .forget(
                        cloud,
                        held.stream().filter(vm -> vm.getVmRef().equals(vmRef)).toList());
        return HttpResponses.redirectViaContextPath("/manage");
    }
}
