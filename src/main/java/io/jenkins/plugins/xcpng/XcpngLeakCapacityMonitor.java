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
 * Tells administrators when leaked VMs are holding a cloud's instance slots (#312).
 *
 * <p>A leaked VM counts against {@code maxInstances} until the sweep destroys it, so a pool that keeps refusing
 * destroys, or a VM the sweep may not touch, can fill the cap and stop provisioning. Builds then sit in the queue
 * with nothing in the log at the moment they stop being served. This names each cloud whose cap is reduced and by
 * how much, and clears itself once the store holds no such entry.
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

    /** One line per configured cloud whose leaked VMs count against its cap. */
    @NonNull
    public List<String> getRows() {
        List<String> rows = new ArrayList<>();
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof XcpngCloud xcpng) {
                int leaked = xcpng.leakedHoldingCapacity();
                if (leaked > 0) {
                    rows.add(Messages.XcpngLeakCapacityMonitor_Row(xcpng.name, leaked, xcpng.getMaxInstances()));
                }
            }
        }
        return rows;
    }
}
