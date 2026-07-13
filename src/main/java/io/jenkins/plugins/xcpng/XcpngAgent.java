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

/**
 * An ephemeral inbound agent backed by one cloned XCP-ng VM.
 *
 * <p>Inbound/JNLP by design: the agent dials out to the controller, so the plugin needs no IP
 * discovery and no controller-to-agent reachability. The clone's {@link VmRef} value is held here so
 * that terminating the node destroys the VM and its disks; the reference back to the owning cloud is
 * by name, since the {@link XcpngCloud} instance is not serialised with the node.
 */
public class XcpngAgent extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(XcpngAgent.class.getName());

    /** Working directory for the agent on the golden image. */
    private static final String REMOTE_FS = "/home/jenkins/agent";

    private final String cloudName;
    private final String vmRef;

    public XcpngAgent(
            @NonNull String name,
            @NonNull String cloudName,
            @NonNull String vmRef,
            @NonNull XcpngTemplate template,
            int idleMinutes)
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
    }

    /** The VM this agent runs on, as an opaque backend handle. */
    @NonNull
    public String getVmRef() {
        return vmRef;
    }

    /** The cloud that provisioned this agent, or null if it has since been removed from the config. */
    @CheckForNull
    public XcpngCloud getCloud() {
        Cloud cloud = Jenkins.get().clouds.getByName(cloudName);
        return cloud instanceof XcpngCloud xcpng ? xcpng : null;
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
