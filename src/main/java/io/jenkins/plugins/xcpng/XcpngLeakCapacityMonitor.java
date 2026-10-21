package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.slaves.Cloud;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;

/**
 * Tells administrators when leaked VMs are holding a cloud's instance slots, or sit on the pool for good (#312).
 *
 * <p>A leaked VM the sweep is still retrying counts against {@code maxInstances}, so a pool that keeps refusing
 * destroys can fill the cap and stop provisioning, with nothing in the log at the moment builds stop being served.
 * A leaked VM no retry will reclaim (held, or not marked as the cloud's) does not count, but stays on the pool
 * until someone removes it. This names each cloud with either, and clears itself once the store holds neither.
 *
 * <p>Computed on each read, like {@link XcpngHeldLeakMonitor}.
 */
@Extension
@Symbol("xcpngLeakCapacity")
public class XcpngLeakCapacityMonitor extends AdministrativeMonitor {

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.XcpngLeakCapacityMonitor_DisplayName();
    }

    @Override
    public boolean isActivated() {
        return !getRows().isEmpty();
    }

    /** Lines per configured cloud: leaked VMs holding its cap, and leaked VMs only a person can remove. */
    @NonNull
    public List<String> getRows() {
        List<String> rows = new ArrayList<>();
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof XcpngCloud xcpng) {
                int leaked = xcpng.leakedHoldingCapacity();
                if (leaked > 0) {
                    rows.add(Messages.XcpngLeakCapacityMonitor_Row(xcpng.name, leaked, xcpng.getMaxInstances()));
                }
                int stuck = xcpng.leakedNeedingAPerson();
                if (stuck > 0) {
                    rows.add(Messages.XcpngLeakCapacityMonitor_Stuck(xcpng.name, stuck));
                }
            }
        }
        return rows;
    }
}
