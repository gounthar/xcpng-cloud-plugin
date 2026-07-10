package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import java.util.Collection;
import java.util.List;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Provisions ephemeral build agents on an XCP-ng pool.
 *
 * <p>M0 scaffold: registers in <em>Manage Jenkins &gt; Clouds</em> and round-trips its
 * configuration. Provisioning arrives in M3, once the XAPI client (M1) and the inbound
 * agent connection (M2) are each proven separately.
 */
public class XcpngCloud extends Cloud {

    private final String poolUrl;

    @DataBoundConstructor
    public XcpngCloud(@NonNull String name, String poolUrl) {
        super(name);
        this.poolUrl = poolUrl;
    }

    public String getPoolUrl() {
        return poolUrl;
    }

    @Override
    public boolean canProvision(CloudState state) {
        return false;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        return List.of();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "XCP-ng";
        }
    }
}
