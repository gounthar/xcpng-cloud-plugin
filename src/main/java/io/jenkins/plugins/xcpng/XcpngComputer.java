package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.slaves.AbstractCloudComputer;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

/**
 * The {@link hudson.model.Computer} for an {@link XcpngAgent}.
 *
 * <p>Implements {@link TrackedItem} so cloud-stats can move the provisioning activity into its launching
 * and operating phases as the computer connects: it reports the same activity id as its node.
 */
public class XcpngComputer extends AbstractCloudComputer<XcpngAgent> implements TrackedItem {

    public XcpngComputer(XcpngAgent agent) {
        super(agent);
    }

    /**
     * The activity id of the backing agent, or null once the node has been removed (cloud-stats treats a
     * null id as "no longer tracked", which is the correct reading of a computer whose node is gone).
     */
    @Override
    @CheckForNull
    public ProvisioningActivity.Id getId() {
        XcpngAgent node = getNode();
        return node == null ? null : node.getId();
    }
}
