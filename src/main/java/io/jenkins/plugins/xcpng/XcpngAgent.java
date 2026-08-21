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
 * by name, since the {@link XcpngCloud} instance is not serialised with the node. Because a name can
 * stop resolving — the cloud deleted, or renamed out from under a running agent — the node also carries
 * a snapshot of that cloud's non-secret connection parameters, so teardown can still reach the pool and
 * destroy the VM rather than strand it.
 *
 * <p>Implements {@link TrackedItem} so cloud-stats can tie this node, and its {@link XcpngComputer}, back
 * to the provisioning activity the {@link XcpngCloud} started for it.
 */
public class XcpngAgent extends AbstractCloudSlave implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(XcpngAgent.class.getName());

    private static final long serialVersionUID = 1L;

    /**
     * Working directory for the agent on the golden image. Must match the {@code -workDir} the golden
     * image's {@code jenkins-agent} systemd unit passes to the agent, which runs as the {@code debian}
     * user: Jenkins runs builds under {@code REMOTE_FS/workspace}, so a mismatch here would send builds
     * to a path the agent user cannot write.
     */
    private static final String REMOTE_FS = "/home/debian/agent";

    /**
     * Executors per agent, fixed at one and deliberately not configurable.
     *
     * <p>Single-use is the whole design: one build, one pristine VM, then the VM and its disks are
     * destroyed. That is incompatible with a second executor. The reap fires when a build completes
     * (see {@link XcpngRetentionStrategy#taskCompleted}), so on a two-executor agent the first build to
     * finish would hard-destroy the VM out from under the second, which dies with a channel loss and no
     * way for the operator to tell why. Throughput comes from more clones, not more executors per clone.
     */
    static final int EXECUTORS_PER_AGENT = 1;

    /**
     * Node usage mode, fixed at {@link Node.Mode#EXCLUSIVE} and deliberately not configurable.
     *
     * <p>{@code NORMAL} means "use this node as much as possible", which makes builds carrying no label
     * expression eligible for these VMs. On a single-use agent that is not a scheduling nicety. A warm
     * spare held hot for a template's labels can be taken by the first unlabeled job to queue, and since
     * the agent is destroyed when that build finishes, the labeled build the pool exists for then waits
     * for a cold clone — the exact latency the warm pool was configured to remove. On a controller whose
     * built-in executors are busy or set to 0, it also turns ordinary unlabeled load into one
     * clone-boot-destroy cycle per build, against a pool the administrator never routed those jobs to.
     *
     * <p>{@code EXCLUSIVE} restricts these agents to builds whose label expression matches the template's
     * labels. {@link XcpngCloud} declines to provision for a null label for the same reason, and the two
     * halves have to stay agreed: core's {@code UnlabeledLoadStatistics} counts only {@code NORMAL} nodes,
     * so an EXCLUSIVE agent contributes no unlabeled capacity. A cloud that still answered unlabeled
     * demand would clone VMs that could never run the build that asked for them, and keep doing it.
     */
    static final Node.Mode USAGE_MODE = Node.Mode.EXCLUSIVE;

    private final String cloudName;
    private final String vmRef;

    /**
     * Connection parameters copied off the owning cloud at provision time, so this agent can still reach the
     * pool to destroy its VM when {@link #getCloud()} no longer resolves — the cloud deleted from the
     * configuration, or renamed, which is the same thing to an agent holding a name.
     *
     * <p>Non-secret by construction, and deliberately the same three values the cloud persists: the pool URL,
     * the <em>ID</em> of the XAPI credential, and whether to trust a self-signed pool certificate. The
     * credential itself is still resolved from the store at point of use, so nothing secret reaches the node's
     * {@code config.xml}. Null on an agent persisted before this snapshot existed; {@link #_terminate} treats
     * that absence as "no fallback available" rather than normalising it, since there is nothing to normalise
     * it to.
     */
    @CheckForNull
    private final String poolUrl;

    @CheckForNull
    private final String credentialsId;

    /**
     * The pinned certificate fingerprint from the cloud that provisioned this agent, or null for ordinary
     * verification. Snapshotted for the same reason as the two fields above: teardown must still reach the
     * pool when the owning cloud has been deleted or renamed. An agent persisted before this field existed
     * reloads with null and connects with ordinary verification, which fails closed against a self-signed
     * pool rather than falling back to trusting it -- there is no longer a setting that could.
     */
    @CheckForNull
    private final String certificateFingerprint;

    /**
     * The cloud-stats provisioning activity this agent belongs to. Serialisable and persisted with the
     * node so a controller restart keeps the correlation; {@link #getId()} hands it to cloud-stats.
     */
    private final ProvisioningActivity.Id activityId;

    /**
     * True while this is an unused warm-pool spare: pre-booted and idle, waiting for its first build.
     * The retention strategy leaves such an agent alone instead of idle-reaping it (that is the point of
     * a warm pool). Cleared the moment it accepts work, after which it behaves like any single-use agent.
     *
     * <p>Transient: it lives only for this controller session. On a restart a reloaded agent comes back
     * not-warm by default, which is deliberate. It guarantees a <em>used</em> agent can never be revived
     * as a spare and run a second build on a no-longer-pristine VM, so single-use holds by construction;
     * the only cost is that genuine unused spares are reaped and re-booted after a restart (see
     * {@link #reloaded}, which is what carries out that reap). Volatile because the retention thread reads
     * it while the executor thread clears it.
     */
    private transient volatile boolean warm;

    /**
     * True on an agent restored from disk by a controller restart. Set in {@link #readResolve} and nowhere
     * else, which is what makes it exact: {@code readResolve} runs on deserialization only, so this marks
     * precisely the agents that outlived a restart and no others.
     *
     * <p>The retention strategy reclaims such an agent rather than waiting out {@code idleMinutes} (see
     * {@link XcpngRetentionStrategy#check}). Both reasons for that are about a restart erasing the state
     * single-use relies on. {@link #warm} is transient, so a spare and a spent agent are indistinguishable
     * once reloaded; and {@code SlaveComputer.acceptingTasks} is transient too, defaulting to {@code true}
     * in its constructor, so the {@code setAcceptingTasks(false)} that {@link
     * XcpngRetentionStrategy#taskAccepted} applied to a used agent does not survive either. An agent whose
     * build finished just before the controller died therefore comes back accepting work on a VM that is no
     * longer pristine. Reaping every reloaded agent is what closes that, and it cannot be narrowed to
     * warm-only spares without persisting the very distinction {@link #warm}'s javadoc says must not exist.
     *
     * <p>Volatile for the same reason as {@link #warm}: the retention thread reads it off a different thread
     * from the one that deserialized the node.
     */
    private transient volatile boolean reloaded;

    /**
     * How a client is opened from the {@link #poolUrl} snapshot when the owning cloud is gone. Null in
     * production, where {@link #openClientFromSnapshot()} builds an {@code XapiClient} through
     * {@link XcpngCloud#openClient(String, String, boolean, String)}; a test injects an in-memory fake and
     * asserts the snapshot it was handed. Transient: behaviour, not configuration, and never persisted.
     */
    private transient ConnectionClientFactory connectionClientFactory;

    /**
     * Wrap a freshly cloned, running VM in a single-use inbound agent. Called from
     * {@link XcpngCloud#provisionNode}, which is the only site that builds one.
     *
     * @param name the node name, which is also the clone's VM name and the identity its inbound agent
     *     presents to the controller.
     * @param cloud the cloud provisioning this agent. Passed whole rather than by name so the connection
     *     snapshot cannot drift from the name it is recorded against; the agent keeps only the name and the
     *     three non-secret connection parameters, never a reference to the cloud itself, which is not
     *     serialised with the node.
     * @param vmRef the clone's opaque backend handle, destroyed with its disks when the node is removed.
     * @param template the template this agent was cloned from, which supplies its labels.
     * @param idleMinutes the timeout handed to this agent's {@link XcpngRetentionStrategy}.
     * @param activityId the cloud-stats activity to correlate this agent's phases against. The same
     *     instance the planned node carries, since cloud-stats matches ids per instance.
     * @param warm whether this is a warm-pool spare, exempt from the idle reap until it takes its first
     *     build.
     */
    public XcpngAgent(
            @NonNull String name,
            @NonNull XcpngCloud cloud,
            @NonNull String vmRef,
            @NonNull XcpngTemplate template,
            int idleMinutes,
            @NonNull ProvisioningActivity.Id activityId,
            boolean warm)
            throws Descriptor.FormException, IOException {
        super(
                name,
                "XCP-ng ephemeral agent",
                REMOTE_FS,
                EXECUTORS_PER_AGENT,
                USAGE_MODE,
                template.getLabelString(),
                new JNLPLauncher(),
                new XcpngRetentionStrategy(idleMinutes),
                Collections.emptyList());
        this.cloudName = cloud.name;
        this.vmRef = vmRef;
        this.activityId = activityId;
        this.warm = warm;
        this.poolUrl = cloud.getPoolUrl();
        this.credentialsId = cloud.getCredentialsId();
        this.certificateFingerprint = cloud.getCertificateFingerprint();
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

    /** Pool URL snapshotted from the owning cloud; null on an agent persisted before the snapshot existed. */
    @CheckForNull
    public String getPoolUrl() {
        return poolUrl;
    }

    /** ID of the XAPI credential snapshotted from the owning cloud, never the credential itself. */
    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    /** Whether the owning cloud was configured to trust a self-signed pool certificate. */
    @CheckForNull
    public String getCertificateFingerprint() {
        return certificateFingerprint;
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

    /** True while this is an unused warm-pool spare the retention strategy should not idle-reap. */
    public boolean isWarm() {
        return warm;
    }

    /** True on an agent restored from disk by a controller restart, which the retention strategy reclaims. */
    public boolean isReloaded() {
        return reloaded;
    }

    /**
     * Mark this spare as used, so it is no longer exempt from idle-reaping and reverts to single-use
     * behaviour. Called when the agent accepts its first build. Idempotent, and a plain field write:
     * {@link #warm} is transient, so there is nothing to persist and a restart resets every agent to
     * not-warm regardless.
     */
    public void markUsed() {
        warm = false;
    }

    /**
     * On reload, force {@link #warm} back to not-warm. The field is transient runtime state, so a reloaded
     * agent must never come back as a spare: that guarantees a used agent cannot be revived and run a
     * second build on a dirty VM. Reassigning the transient field here (rather than relying on the default)
     * also keeps serialization analysis satisfied that it is handled, matching {@link XcpngCloud#readResolve}.
     *
     * <p>{@link #reloaded} is raised for the other half of the same problem. Clearing {@code warm} stops a
     * spent agent being kept hot, but on its own it leaves that agent sitting there as an ordinary idle node,
     * accepting work again (the computer's accepting-tasks flag does not survive a restart either) until
     * {@code idleMinutes} expires. The flag tells the retention strategy to reclaim it now instead.
     *
     * <p>The usage mode is re-applied for a different reason. {@code Slave.mode} is a persisted field, not a
     * transient one, and the constructor does not run on deserialization: an agent whose {@code config.xml}
     * was written before {@link #USAGE_MODE} became {@code EXCLUSIVE} carries {@code NORMAL} and would come
     * back able to accept unlabeled builds. That is exactly the bug the mode exists to prevent, surviving a
     * controller restart, so the mode is asserted here rather than trusted from the file.
     */
    protected Object readResolve() {
        warm = false;
        reloaded = true;
        setMode(USAGE_MODE);
        return super.readResolve();
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
            terminateWithoutCloud(listener);
            return;
        }
        listener.getLogger().println("Destroying XCP-ng VM " + vmRef + " and its disks.");
        try (HypervisorClient client = cloud.openClient()) {
            client.destroyWithDisks(new VmRef(vmRef));
        } catch (RuntimeException e) {
            // The base class removes this node in a finally regardless of what happens here, so once we
            // return nothing in Jenkins references vmRef any more: a swallowed failure would leak the VM
            // and its disks for the controller's whole life. Hand the ref to the cloud's durable orphan set
            // instead, so XcpngWarmPoolMaintainer can reissue the destroy on a later tick, across a restart.
            // Logged at SEVERE, not WARNING: a leaked VM holds real pool storage until reclaimed.
            LOGGER.log(
                    Level.SEVERE,
                    e,
                    () -> "Failed to destroy VM " + vmRef + " for agent " + getNodeName()
                            + "; recorded it as a leaked VM for the warm-pool maintainer to reclaim");
            listener.getLogger()
                    .println("Failed to destroy VM " + vmRef + "; recorded for later cleanup: " + e.getMessage());
            cloud.recordLeakedVm(vmRef);
        }
    }

    /**
     * Destroy the VM without an owning cloud to ask, using the connection snapshot taken at provision time.
     *
     * <p>The cloud being gone is not an edge case an administrator has to go looking for: deleting a cloud
     * while its agents run does it, and so does renaming one, since the node holds a name and the UI replaces
     * the instance under the new one. Before the snapshot existed this method's predecessor logged a warning
     * and returned, and because the base class removes the node in a {@code finally} either way, that return
     * dropped the last reference to {@link #vmRef} — one VM and its copy-on-write disks stranded on the pool
     * per running agent, recoverable only by hand with {@code tools/reaper.py}.
     *
     * <p>Two cases still strand the VM, and both are logged at SEVERE and named on the build log because
     * nothing downstream can recover them: an agent persisted before the snapshot existed, which reloads with
     * no parameters to connect with, and a snapshot that no longer opens — its credential deleted from the
     * store, or the pool unreachable. The cloud's durable leaked-VM set cannot stand in for either, since
     * {@link XcpngCloud#recordLeakedVm} lives on the very cloud that no longer exists.
     */
    private void terminateWithoutCloud(TaskListener listener) {
        if (poolUrl == null) {
            LOGGER.log(
                    Level.SEVERE,
                    () -> "Cloud '" + cloudName + "' is gone and agent " + getNodeName()
                            + " predates the connection snapshot, so VM " + vmRef
                            + " cannot be destroyed; reclaim it with tools/reaper.py");
            listener.getLogger()
                    .println("XCP-ng cloud '" + cloudName + "' not found and this agent holds no connection"
                            + " details; VM " + vmRef + " is orphaned. Reclaim it with tools/reaper.py.");
            return;
        }
        LOGGER.log(
                Level.WARNING,
                () -> "Cloud '" + cloudName + "' is gone; destroying VM " + vmRef + " for agent " + getNodeName()
                        + " from the connection snapshot taken when it was provisioned");
        listener.getLogger()
                .println("XCP-ng cloud '" + cloudName + "' not found; destroying VM " + vmRef
                        + " from this agent's connection snapshot.");
        try (HypervisorClient client = openClientFromSnapshot()) {
            client.destroyWithDisks(new VmRef(vmRef));
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.SEVERE,
                    e,
                    () -> "Failed to destroy VM " + vmRef + " for agent " + getNodeName() + " after cloud '"
                            + cloudName + "' was removed; no cloud remains to retry it, so reclaim the VM with"
                            + " tools/reaper.py");
            listener.getLogger()
                    .println("Failed to destroy VM " + vmRef + " and cloud '" + cloudName
                            + "' is gone, so it cannot be retried: " + e.getMessage()
                            + ". Reclaim it with tools/reaper.py.");
        }
    }

    /**
     * Open a session to the pool from this agent's connection snapshot. Production resolves the credential
     * from the store and builds an {@code XapiClient} through the same helper {@link XcpngCloud#openClient()}
     * uses, so the two paths cannot differ on TLS trust or credential scoping; a test injects a fake through
     * {@link #setConnectionClientFactory} and asserts the snapshot it receives. The caller owns the returned
     * client and must close it.
     */
    @NonNull
    private HypervisorClient openClientFromSnapshot() {
        if (connectionClientFactory != null) {
            return connectionClientFactory.open(poolUrl, credentialsId, certificateFingerprint);
        }
        return XcpngCloud.openClient(
                poolUrl, credentialsId, certificateFingerprint, "the removed cloud '" + cloudName + "'");
    }

    /** Test seam: replace how the cloud-gone fallback opens a client with an in-memory fake. */
    void setConnectionClientFactory(ConnectionClientFactory connectionClientFactory) {
        this.connectionClientFactory = connectionClientFactory;
    }

    /**
     * How {@link #openClientFromSnapshot()} obtains a client. Production leaves this null; a test supplies an
     * in-memory fake, which is handed the snapshot itself rather than this agent, so a test can assert the
     * three parameters actually survived provisioning. Not {@code Serializable} on purpose: it is held only in
     * the transient {@link #connectionClientFactory} field and never reaches the node's {@code config.xml}.
     */
    @FunctionalInterface
    interface ConnectionClientFactory {

        /**
         * Open a session to the pool these parameters describe. The caller owns the returned client and
         * closes it; an implementation that cannot open one throws, and the cloud-gone teardown treats
         * that as the unrecoverable case rather than a retryable one.
         *
         * @param poolUrl the snapshotted pool URL, null on an agent predating the snapshot.
         * @param credentialsId the snapshotted credential ID, resolved against the store by the caller.
         * @param certificateFingerprint the pinned certificate fingerprint the removed cloud used, or
         *     null for ordinary verification against the JVM trust store.
         */
        @NonNull
        HypervisorClient open(
                @CheckForNull String poolUrl,
                @CheckForNull String credentialsId,
                @CheckForNull String certificateFingerprint);
    }

    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.XcpngAgent_DisplayName();
        }

        /** Provisioned only by {@link XcpngCloud}, never created by hand from the New Node form. */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
