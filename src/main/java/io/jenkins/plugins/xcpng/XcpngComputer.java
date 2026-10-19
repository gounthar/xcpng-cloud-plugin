package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.slaves.AbstractCloudComputer;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import jenkins.model.Jenkins;
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

    /**
     * Replacing an XCP-ng agent's whole definition is reserved to administrators.
     *
     * <p>Its fields are not an operator's to set: the pool URL, credential ID, TLS trust, VM handle and owning
     * cloud are written by the plugin when it provisions, from a cloud only an administrator configures, and
     * {@link XcpngAgent#reconfigure} already keeps them off the HTML form. Reading the definition stays at
     * {@code EXTENDED_READ}, as core has it: none of those fields is a secret.
     */
    @Override
    public void updateByXml(InputStream source) throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        super.updateByXml(source);
    }
}
