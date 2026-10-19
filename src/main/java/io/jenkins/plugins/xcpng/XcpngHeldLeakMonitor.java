package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import java.util.List;
import java.util.Map;
import org.jenkinsci.Symbol;

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
}
