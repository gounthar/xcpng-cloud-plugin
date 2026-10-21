package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.slaves.AbstractCloudComputer;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.stapler.HttpResponses;

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
     * Replacing an XCP-ng agent's whole definition is refused, to everyone (#354).
     *
     * <p>Both routes in, a POST to {@code config.xml} and the {@code update-node} CLI command, land here. Core
     * builds the replacement by deserializing the submitted XML, so it comes out of {@link
     * XcpngAgent#readResolve} marked reloaded, exactly like an agent restored by a restart, and the retention
     * check core runs on {@code setNode} reaps it: an idle agent and its VM are destroyed within a second, a
     * busy one when its build ends. That reap is deliberate for a restart and cannot tell the two apart.
     *
     * <p>Carrying the old instance's runtime state across the replace would stop the reap, but not the second
     * problem: the submitted XML carries the VM handle, owning cloud and pool URL, so a replace could point
     * this node's teardown at a VM the plugin never cloned. The editable fields, labels and description, are
     * on the configure form, which {@link XcpngAgent#reconfigure} applies to the same instance.
     *
     * <p>The permission check stays first, so a caller without {@code ADMINISTER} is told that rather than
     * the refusal. Reading the definition stays at {@code EXTENDED_READ}, as core has it: none of its fields
     * is a secret.
     */
    @Override
    public void updateByXml(InputStream source) throws IOException, ServletException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        throw HttpResponses.errorWithoutStack(
                HttpServletResponse.SC_BAD_REQUEST, Messages.XcpngComputer_updateByXml_refused());
    }
}
