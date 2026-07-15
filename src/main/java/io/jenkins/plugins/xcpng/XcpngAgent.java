package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.VmRef;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

/**
 * An ephemeral inbound agent backed by one cloned XCP-ng VM.
 *
 * <p>Inbound/JNLP by design: the agent dials out to the controller, so the plugin needs no IP
 * discovery and no controller-to-agent reachability. The clone's {@link VmRef} value is held here so
 * that terminating the node destroys the VM and its disks; the reference back to the owning cloud is
 * by name, since the {@link XcpngCloud} instance is not serialised with the node.
 *
 * <p>Implements {@link TrackedItem} so cloud-stats can tie this node, and its {@link XcpngComputer}, back
 * to the provisioning activity the {@link XcpngCloud} started for it.
 */
public class XcpngAgent extends AbstractCloudSlave implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(XcpngAgent.class.getName());

    /**
     * Working directory for the agent on the golden image. Must match the {@code -workDir} the golden
     * image's {@code jenkins-agent} systemd unit passes to the agent, which runs as the {@code debian}
     * user: Jenkins runs builds under {@code REMOTE_FS/workspace}, so a mismatch here would send builds
     * to a path the agent user cannot write.
     */
    private static final String REMOTE_FS = "/home/debian/agent";

    private final String cloudName;
    private final String vmRef;

    /**
     * The cloud-stats provisioning activity this agent belongs to. Serialisable and persisted with the
     * node so a controller restart keeps the correlation; {@link #getId()} hands it to cloud-stats.
     */
    private final ProvisioningActivity.Id activityId;

    public XcpngAgent(
            @NonNull String name,
            @NonNull String cloudName,
            @NonNull String vmRef,
            @NonNull XcpngTemplate template,
            int idleMinutes,
            @NonNull ProvisioningActivity.Id activityId)
            throws Descriptor.FormException, IOException {
        super(
                name,
                "XCP-ng ephemeral agent",
                REMOTE_FS,
                template.getNumExecutors(),
                Node.Mode.NORMAL,
                template.getLabelString(),
                new JNLPLauncher(),
                new XcpngRetentionStrategy(idleMinutes),
                Collections.emptyList());
        this.cloudName = cloudName;
        this.vmRef = vmRef;
        this.activityId = activityId;
    }

    /** The VM this agent runs on, as an opaque backend handle. */
    @NonNull
    public String getVmRef() {
        return vmRef;
    }

    /** Name of the cloud that provisioned this agent, used to attribute it against that cloud's cap. */
    @NonNull
    public String getCloudName() {
        return cloudName;
    }

    /** The cloud that provisioned this agent, or null if it has since been removed from the config. */
    @CheckForNull
    public XcpngCloud getCloud() {
        Cloud cloud = Jenkins.get().clouds.getByName(cloudName);
        return cloud instanceof XcpngCloud xcpng ? xcpng : null;
    }

    /** The cloud-stats activity this agent was provisioned under; never null in practice. */
    @Override
    @CheckForNull
    public ProvisioningActivity.Id getId() {
        return activityId;
    }

    @Override
    public AbstractCloudComputer<XcpngAgent> createComputer() {
        return new XcpngComputer(this);
    }

    /**
     * Destroy the backing VM and its disks. Called by the base class when the node is removed, so all
     * teardown paths (single-use completion, idle timeout, manual delete) converge here. Failures are
     * logged rather than thrown: the node is going away regardless, and a thrown exception would only
     * leave it half-removed.
     */
    @Override
    protected void _terminate(TaskListener listener) {
        XcpngCloud cloud = getCloud();
        if (cloud == null) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "Cloud '" + cloudName + "' is gone; VM " + vmRef + " for agent " + getNodeName()
                            + " may be orphaned");
            listener.getLogger()
                    .println("XCP-ng cloud '" + cloudName + "' not found; VM " + vmRef + " may be orphaned.");
            return;
        }
        listener.getLogger().println("Destroying XCP-ng VM " + vmRef + " and its disks.");
        try (HypervisorClient client = cloud.openClient()) {
            client.destroyWithDisks(new VmRef(vmRef));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Failed to destroy VM " + vmRef + " for agent " + getNodeName());
            listener.getLogger().println("Failed to destroy VM " + vmRef + ": " + e.getMessage());
        }
    }

    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "XCP-ng agent";
        }

        /** Provisioned only by {@link XcpngCloud}, never created by hand from the New Node form. */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
