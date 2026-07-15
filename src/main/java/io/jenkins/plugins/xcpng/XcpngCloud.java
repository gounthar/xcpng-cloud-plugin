package io.jenkins.plugins.xcpng;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.ProvisionSpec;
import io.jenkins.plugins.xcpng.client.VmRef;
import io.jenkins.plugins.xcpng.client.XapiClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpAgentReceiver;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

/**
 * Provisions ephemeral build agents on an XCP-ng pool.
 *
 * <p>The plugin's {@code config.xml} holds only non-secrets and a credential ID: the pool URL, the
 * ID of the XAPI username/password credential, whether to trust a self-signed pool certificate, an
 * instance cap, and the agent templates. The secret itself is resolved from the credentials store at
 * point of use and never written here.
 *
 * <p>M3 slice 4: {@link #provision} clones the matching template, starts it, and hands back a
 * single-use inbound {@link XcpngAgent}. The clone's VM is destroyed with its disks when the agent
 * terminates (after one build, or an idle timeout).
 */
public class XcpngCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(XcpngCloud.class.getName());

    /**
     * Default idle timeout, in minutes, before an agent that connected but never received work is
     * reclaimed. Applied when {@link #idleMinutes} is left unset in the form or JCasC, and as the
     * floor a non-positive configured value is clamped to: the idle reap is the only safety net for a
     * clone that boots but never connects, so disabling it entirely would leak that VM forever.
     */
    private static final int DEFAULT_IDLE_MINUTES = 10;

    /** How long to wait for a provisioned agent to connect before giving up and tearing its VM down. */
    private static final int ONLINE_TIMEOUT_MINUTES = 5;

    private static final long ONLINE_POLL_MILLIS = 1000L;

    /**
     * Dedicated pool for provisioning tasks. Each task blocks up to {@link #ONLINE_TIMEOUT_MINUTES}
     * waiting for its agent to connect, so it runs here rather than on {@code Computer.threadPoolForRemoting}
     * where long waits would compete with the controller's own remoting work. Cached and daemon-threaded:
     * it grows to the number of in-flight provisions (bounded per cloud by {@code maxInstances}) and idles
     * back to zero.
     */
    private static final ExecutorService PROVISION_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "xcpng-provisioner");
        thread.setDaemon(true);
        return thread;
    });

    private final String poolUrl;
    private final String credentialsId;
    private final boolean trustSelfSigned;
    // Not final: readResolve re-applies the constructor's guards when XStream loads an older config
    // that predates these fields (the constructor does not run on deserialization).
    private int maxInstances;
    private List<XcpngTemplate> templates;

    /**
     * Idle timeout, in minutes, before an agent that connected but never received work is reclaimed.
     * Optional: a {@link DataBoundSetter} rather than a constructor parameter, so an older config or a
     * JCasC document that omits it keeps the {@link #DEFAULT_IDLE_MINUTES} field initializer. The
     * dominant path is single-use (reaped after one build); this is only the safety net for a clone that
     * boots but never connects. Not final: {@link #readResolve} re-applies the clamp on deserialization.
     */
    private int idleMinutes = DEFAULT_IDLE_MINUTES;

    /**
     * How a live client is opened. Null in production, where {@link #openClient()} builds an
     * {@link XapiClient} from the configured credentials; a test injects an in-memory fake here.
     * Transient: it is behaviour, not configuration, and must never be persisted to {@code config.xml}.
     */
    private transient HypervisorClientFactory clientFactory;

    /**
     * Provisions submitted but not yet registered as {@link XcpngAgent} nodes. Counted against the
     * instance cap so a burst of concurrent {@link #provision} rounds cannot overshoot {@code
     * maxInstances} while earlier clones are still booting and have not yet appeared in the node list.
     * Transient and never persisted; the field initializer covers a fresh instance and {@link
     * #readResolve} covers a deserialized one (XStream skips both the constructor and field initializers).
     */
    private transient AtomicInteger inFlight = new AtomicInteger();

    /**
     * Executor for provisioning submits. Null in production, where {@link #provisionExecutor()} falls
     * back to the dedicated {@link #PROVISION_POOL}; a test injects a controllable one. Transient:
     * behaviour, never persisted to {@code config.xml}.
     */
    private transient ExecutorService provisionExecutor;

    /**
     * Whether a provisioning task registers the node and waits for it to come online before completing
     * the planned-node future (the production behaviour, which stops the NodeProvisioner over-provisioning).
     * A test flips this off so the fake, never-connecting agents complete immediately. Transient; the
     * field initializer covers a fresh instance and {@link #readResolve} a deserialized one.
     */
    private transient boolean waitForOnline = true;

    @DataBoundConstructor
    public XcpngCloud(
            @NonNull String name,
            String poolUrl,
            String credentialsId,
            boolean trustSelfSigned,
            int maxInstances,
            List<XcpngTemplate> templates) {
        super(name);
        // Trim on the way in so the persisted value matches what the validator parses; a stray space
        // would otherwise validate in the form yet break URI.create when the endpoint is built.
        this.poolUrl = poolUrl == null ? null : poolUrl.trim();
        this.credentialsId = credentialsId;
        this.trustSelfSigned = trustSelfSigned;
        this.maxInstances = maxInstances <= 0 ? 1 : maxInstances;
        this.templates = templates == null ? new ArrayList<>() : new ArrayList<>(templates);
    }

    /**
     * XStream reloads global configuration without running the {@link DataBoundConstructor}, so an
     * older or hand-edited {@code config.xml} can arrive with no {@code <templates>} element (leaving
     * the list null, which would make {@link #getTemplates()} throw) or a zero {@code maxInstances}
     * that skipped the constructor's guard. Re-apply those guards on the way in.
     */
    protected Object readResolve() {
        if (templates == null) {
            templates = new ArrayList<>();
        }
        if (maxInstances <= 0) {
            maxInstances = 1;
        }
        // A config predating this field deserializes it to 0 (XStream skips the initializer); a
        // non-positive value would disable the idle safety net, so restore the default.
        if (idleMinutes <= 0) {
            idleMinutes = DEFAULT_IDLE_MINUTES;
        }
        if (inFlight == null) {
            inFlight = new AtomicInteger();
        }
        // Transient boolean: XStream skips the field initializer, so a reloaded cloud would default to
        // false (fast-complete) and over-provision. Restore the production behaviour.
        waitForOnline = true;
        return this;
    }

    public String getPoolUrl() {
        return poolUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public boolean isTrustSelfSigned() {
        return trustSelfSigned;
    }

    public int getMaxInstances() {
        return maxInstances;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    /**
     * Optional idle timeout. Clamped to {@link #DEFAULT_IDLE_MINUTES} for any non-positive value so the
     * safety-net reap is never switched off, mirroring how {@code maxInstances} floors at one.
     */
    @DataBoundSetter
    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes <= 0 ? DEFAULT_IDLE_MINUTES : idleMinutes;
    }

    @NonNull
    public List<XcpngTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    @Override
    public boolean canProvision(CloudState state) {
        return templateFor(state.getLabel()) != null && availableCapacity() > 0;
    }

    // Synchronized so two concurrent provisioning rounds cannot both snapshot the same capacity before
    // either reserves against it; without that lock each could plan up to the cap and overshoot.
    @Override
    public synchronized Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        XcpngTemplate template = templateFor(state.getLabel());
        if (template == null) {
            return List.of();
        }
        List<NodeProvisioner.PlannedNode> planned = new ArrayList<>();
        int capacity = availableCapacity();
        int remaining = excessWorkload;
        // One VM per planned node; each serves numExecutors of the excess workload. Stop when the
        // workload is met or the instance cap is reached, whichever comes first.
        while (remaining > 0 && planned.size() < capacity) {
            // A random suffix, not a counter: an in-process counter resets on controller restart, so a
            // crash that leaves an old node behind could hand a new clone the same name and collide in
            // the pool. A UUID segment stays unique across restarts.
            final String displayName = "xcpng-" + template.getTemplateName() + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
            // The cloud-stats activity id for this provisioning. One instance is created here and shared:
            // the TrackedPlannedNode below and the XcpngAgent built by provisionNode both carry this exact
            // object. cloud-stats' Id equality is per-instance (a random fingerprint, not the names), so a
            // rebuilt id would never correlate; the phases stay on one activity only by sharing this handle.
            final ProvisioningActivity.Id activityId =
                    new ProvisioningActivity.Id(name, template.getTemplateName(), displayName);
            // Reserve capacity before the clone starts and release it when the provision settles, so a
            // concurrent round sees the reservation and the cap holds even while clones are still booting.
            inFlight.incrementAndGet();

            // A CompletableFuture, not the raw submit() future, so cancellation is handled: the node
            // provisioner may cancel a PlannedNode, and if the task never runs (cancelled before start)
            // its own finally would never release the reservation. Releasing in whenComplete instead ties
            // the decrement to the future settling by any path -- success, failure, or cancellation.
            CompletableFuture<Node> future = new CompletableFuture<>();
            future.whenComplete((node, throwable) -> inFlight.decrementAndGet());

            try {
                Future<?> task = provisionExecutor().submit(() -> {
                    if (future.isCancelled()) {
                        return;
                    }
                    Node node = null;
                    try {
                        node = provisionNode(template, displayName, activityId);
                        // Keep the planned node "pending" until the agent actually connects, not merely
                        // until the VM clones (~2s). The NodeProvisioner counts a completed PlannedNode as
                        // delivered capacity, so completing early -- while the node is registered but still
                        // offline -- made it provision a second VM for a single build. Register the node now
                        // so the inbound agent can dial in, then wait for it to come online. On completion
                        // the NodeProvisioner re-adds the same instance, which is a no-op (node == existing).
                        if (waitForOnline) {
                            Jenkins.get().addNode(node);
                            awaitOnline(node, displayName, future);
                        }
                        if (!future.complete(node) && node instanceof XcpngAgent agent) {
                            // The planned node was cancelled while this VM was being built, so it will
                            // never be used. Tear it down rather than leak it on the pool.
                            LOGGER.log(
                                    Level.WARNING,
                                    () -> "Provision of " + displayName
                                            + " completed after cancellation; terminating the orphaned agent");
                            terminateQuietly(agent, displayName);
                        }
                    } catch (Throwable t) {
                        // Preserve the interrupt so higher-level shutdown/cancellation logic still sees it,
                        // rather than swallowing it into the future's exceptional completion.
                        if (t instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        // If the VM was built and registered but never came online (timeout, or a failure
                        // after clone), tear it down so a failed provision leaks neither a VM nor a
                        // half-added offline node.
                        if (node instanceof XcpngAgent agent) {
                            terminateQuietly(agent, displayName);
                        }
                        future.completeExceptionally(t);
                    }
                });
                // Propagate a cancellation of the planned node to the task, but without interrupting it:
                // a not-yet-started task is prevented from running, and a running one is left to finish so
                // its cleanup (provisionNode's post-clone destroy, or the orphan guard above) runs on a
                // thread with no interrupt flag set. Interrupting mid-run could leave the flag set and make
                // the blocking destroyWithDisks call fail, leaking the very VM the cleanup exists to remove.
                future.whenComplete((node, throwable) -> {
                    if (throwable instanceof CancellationException) {
                        task.cancel(false);
                    }
                });
                planned.add(new TrackedPlannedNode(activityId, template.getNumExecutors(), future));
                remaining -= template.getNumExecutors();
            } catch (RejectedExecutionException e) {
                // The pool refused the task, which it does only while shutting down. Settle the future so
                // its whenComplete releases the reservation, then stop planning further nodes.
                LOGGER.log(Level.WARNING, e, () -> "Could not schedule provision of " + displayName);
                future.completeExceptionally(e);
                break;
            }
        }
        return planned;
    }

    /**
     * Executor that runs provisioning tasks. Production uses the dedicated {@link #PROVISION_POOL} so the
     * blocking online-wait never ties up remoting threads; a test injects one via {@link
     * #setProvisionExecutor} to force a rejection or to hold tasks so the in-flight reservation is observable.
     */
    @NonNull
    private ExecutorService provisionExecutor() {
        return provisionExecutor != null ? provisionExecutor : PROVISION_POOL;
    }

    /** Test seam: run provisioning submits on a controllable executor. */
    void setProvisionExecutor(ExecutorService provisionExecutor) {
        this.provisionExecutor = provisionExecutor;
    }

    /** Test seam: reservations taken but not yet settled, for asserting the cap accounting. */
    int inFlightCount() {
        return inFlight.get();
    }

    /**
     * Test seam: with a fake client the provisioned agents never connect, so the online wait would block
     * for the whole timeout. Turning it off makes the planned-node future complete as soon as the VM is
     * "cloned", which is what the capacity/planning tests assert against.
     */
    void setWaitForOnline(boolean waitForOnline) {
        this.waitForOnline = waitForOnline;
    }

    /**
     * Block until the provisioned node's computer connects, or fail after {@link #ONLINE_TIMEOUT_MINUTES}.
     * Returns early without error if the planned node is cancelled while waiting: the caller's cancellation
     * path then tears the VM down.
     */
    private static void awaitOnline(@NonNull Node node, @NonNull String displayName, @NonNull Future<?> future)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + ONLINE_TIMEOUT_MINUTES * 60L * 1_000_000_000L;
        // Re-fetch the computer each pass and treat a null one as "not online yet": returning early on a
        // transiently-null Computer would complete the future without the agent connected, reintroducing
        // the over-provisioning this wait exists to prevent.
        while (true) {
            Computer computer = node.toComputer();
            if (computer != null && computer.isOnline()) {
                return;
            }
            if (future.isCancelled()) {
                return;
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException(
                        "agent " + displayName + " did not come online within " + ONLINE_TIMEOUT_MINUTES + " minutes");
            }
            Thread.sleep(ONLINE_POLL_MILLIS);
        }
    }

    private static void terminateQuietly(@NonNull XcpngAgent agent, @NonNull String displayName) {
        try {
            agent.terminate();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e, () -> "Failed to terminate agent " + displayName);
        }
    }

    /**
     * Clone the template, start it, and wrap the running VM in a single-use inbound agent. Called on a
     * background thread by {@link #provision}; the returned {@link Node} is added to Jenkins by the
     * node provisioner once this future completes. Package-visible so a test can drive it against a
     * fake client and assert the clone/start call sequence. The {@code activityId} is the same instance
     * carried by the planned node, stamped onto the agent so cloud-stats keeps both on one activity.
     */
    Node provisionNode(
            @NonNull XcpngTemplate template, @NonNull String displayName, @NonNull ProvisioningActivity.Id activityId)
            throws Exception {
        try (HypervisorClient client = openClient()) {
            VmRef templateRef = client.resolveTemplate(template.getTemplateName());
            ProvisionSpec spec = new ProvisionSpec(
                    displayName,
                    template.getNumCpus(),
                    template.getMemoryBytes(),
                    null,
                    null,
                    null,
                    seedFor(displayName, template));
            VmRef clone = client.cloneFromTemplate(templateRef, spec);
            try {
                client.start(clone);
                XcpngAgent agent =
                        new XcpngAgent(displayName, name, clone.value(), template, idleMinutes, activityId);
                LOGGER.log(Level.INFO, () -> "Provisioned XCP-ng VM " + clone.value() + " as agent " + displayName);
                return agent;
            } catch (Exception e) {
                // The clone exists but never became a usable agent. Destroy it so a failed provision does
                // not leak a VM and its disks: the reaper iterates VMs by agent, so a clone attached to no
                // agent is invisible to it and would leak indefinitely.
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "Provision of " + displayName + " failed after clone; destroying VM " + clone.value());
                try {
                    client.destroyWithDisks(clone);
                } catch (RuntimeException cleanup) {
                    LOGGER.log(
                            Level.WARNING,
                            cleanup,
                            () -> "Could not clean up VM " + clone.value() + " after a failed provision");
                }
                throw e;
            }
        }
    }

    /**
     * The per-clone seed the guest reads (from xenstore, via {@link ProvisionSpec#guestData()}) to
     * launch its inbound agent unattended: the controller URL it dials, the node name it registers as,
     * and the JNLP secret it must present. The secret is an HMAC of the node name, so it is stable and
     * computable here, before the {@link XcpngAgent} node is added and its computer exists. The URL is
     * omitted when the controller has no root URL configured; the guest then has nothing to dial and the
     * agent is reclaimed by the idle timeout, which is the right outcome for that misconfiguration.
     */
    @NonNull
    private static Map<String, String> seedFor(@NonNull String nodeName, @NonNull XcpngTemplate template) {
        Map<String, String> seed = new LinkedHashMap<>();
        String rootUrl = Jenkins.get().getRootUrl();
        if (rootUrl == null || rootUrl.isBlank()) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "Jenkins root URL is not set; agent " + nodeName
                            + " will have no controller URL to connect back to");
        } else {
            seed.put("url", rootUrl);
        }
        seed.put("name", nodeName);
        seed.put("secret", JnlpAgentReceiver.SLAVE_SECRET.mac(nodeName));
        // Optional operator-supplied public key. Delivered on the same channel; the guest writes it to
        // the debian user's authorized_keys. Absent unless the template sets it, keeping inbound-only
        // clones key-free. The setter already trimmed and null-normalised it.
        String sshKey = template.getSshAuthorizedKey();
        if (sshKey != null) {
            seed.put("ssh_authorized_key", sshKey);
        }
        return seed;
    }

    /**
     * Open a session to the pool. In production this builds an {@link XapiClient} from the configured
     * username/password credential; a test injects a fake via {@link #setClientFactory}. The caller
     * owns the returned client and must close it.
     */
    @NonNull
    HypervisorClient openClient() {
        if (clientFactory != null) {
            return clientFactory.open(this);
        }
        StandardUsernamePasswordCredentials credentials =
                DescriptorImpl.lookupCredentials(poolUrl, credentialsId);
        if (credentials == null) {
            throw new IllegalStateException("No XAPI credentials configured for cloud '" + name + "'.");
        }
        return new XapiClient(
                poolUrl,
                credentials.getUsername(),
                credentials.getPassword().getPlainText(),
                trustSelfSigned);
    }

    /** Test seam: replace how a client is opened with an in-memory fake. */
    void setClientFactory(HypervisorClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /** The first template whose labels satisfy {@code label}; null if none does. */
    @CheckForNull
    private XcpngTemplate templateFor(@CheckForNull Label label) {
        for (XcpngTemplate template : templates) {
            if (labelMatches(label, template)) {
                return template;
            }
        }
        return null;
    }

    private static boolean labelMatches(@CheckForNull Label label, XcpngTemplate template) {
        // A null label is a job with no label constraint; any template may serve it.
        return label == null || label.matches(Label.parse(template.getLabelString()));
    }

    /**
     * Instance-cap headroom: the configured maximum minus the agents <em>this</em> cloud already runs.
     * Filtered by cloud name so one XCP-ng cloud does not throttle another that shares the controller.
     */
    private int availableCapacity() {
        int active = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof XcpngAgent agent && name.equals(agent.getCloudName())) {
                active++;
            }
        }
        // Subtract in-flight provisions too: they will become nodes but have not yet, so counting only
        // registered agents would let a concurrent round provision past the cap.
        return Math.max(0, maxInstances - active - inFlight.get());
    }

    /**
     * How {@link #openClient()} obtains a client. Production leaves this null and builds an
     * {@link XapiClient}; a test supplies an in-memory fake. Not {@code Serializable} on purpose: it is
     * held only in the transient {@link #clientFactory} field and never reaches {@code config.xml}.
     */
    @FunctionalInterface
    interface HypervisorClientFactory {
        @NonNull
        HypervisorClient open(@NonNull XcpngCloud cloud);
    }

    @Extension
    @Symbol("xcpng")
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "XCP-ng";
        }

        /**
         * Resolve the configured XAPI credential. Scoped to the pool URL's domain so a store that
         * partitions credentials by host returns only the relevant ones.
         */
        @CheckForNull
        static StandardUsernamePasswordCredentials lookupCredentials(
                @CheckForNull String poolUrl, @CheckForNull String credentialsId) {
            if (credentialsId == null || credentialsId.isEmpty()) {
                return null;
            }
            return CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentialsInItemGroup(
                            StandardUsernamePasswordCredentials.class,
                            Jenkins.get(),
                            ACL.SYSTEM2,
                            URIRequirementBuilder.fromUri(poolUrl).build()),
                    CredentialsMatchers.withId(credentialsId));
        }

        /**
         * Validate the pool URL as the administrator types, before they reach "Test connection". Blank
         * is left to {@code ok()} so a fresh form does not nag; a non-blank value goes through the same
         * scheme/host check the connection test applies.
         */
        public FormValidation doCheckPoolUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            return validatePoolUrlFormat(value);
        }

        /**
         * Scheme/host check shared by the field validator and {@code doTestConnection}, so a malformed
         * URL is rejected the same way in both. A bare {@code new URI(value)} parse is too permissive:
         * a relative or schemeless string such as {@code 192.168.1.87} parses without error yet is not
         * a pool address, so also assert an http/https scheme and a host. Assumes a non-blank value.
         */
        static FormValidation validatePoolUrlFormat(String value) {
            URI uri;
            try {
                uri = new URI(value.trim());
            } catch (URISyntaxException e) {
                return FormValidation.error("Enter a valid URL, for example https://192.168.1.87.");
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return FormValidation.error("The pool URL must start with http:// or https://.");
            }
            if (uri.getHost() == null) {
                return FormValidation.error("The pool URL must include a host, for example https://192.168.1.87.");
            }
            if (uri.getUserInfo() != null) {
                // Credentials embedded in the URL (https://user:pass@host) would be persisted in
                // config.xml and could reach logs, against the store-the-ID-never-the-secret design.
                return FormValidation.error("Do not put credentials in the pool URL; select them in the Credentials field.");
            }
            return FormValidation.ok();
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(
                @QueryParameter String poolUrl, @QueryParameter String credentialsId) {
            Jenkins jenkins = Jenkins.get();
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            jenkins,
                            StandardUsernamePasswordCredentials.class,
                            URIRequirementBuilder.fromUri(poolUrl).build(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        @RequirePOST
        public FormValidation doTestConnection(
                @QueryParameter String poolUrl,
                @QueryParameter String credentialsId,
                @QueryParameter boolean trustSelfSigned) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (poolUrl == null || poolUrl.isBlank()) {
                return FormValidation.error("The pool URL is required.");
            }
            // Normalise once so the check, the credential lookup, and the client all see the same value
            // the constructor would persist. A fresh local keeps the captured parameter effectively final.
            final String url = poolUrl.trim();
            FormValidation urlCheck = validatePoolUrlFormat(url);
            if (urlCheck.kind != FormValidation.Kind.OK) {
                // Reject a malformed URL with the same message the field validator gives, rather than
                // letting it fail deeper in XapiClient as a less actionable transport error.
                return urlCheck;
            }
            StandardUsernamePasswordCredentials credentials = lookupCredentials(url, credentialsId);
            if (credentials == null) {
                return FormValidation.error("Select the XAPI credentials.");
            }
            try (XapiClient client = new XapiClient(
                    url,
                    credentials.getUsername(),
                    credentials.getPassword().getPlainText(),
                    trustSelfSigned)) {
                client.ping();
                return FormValidation.ok("Connected to the pool.");
            } catch (RuntimeException e) {
                // The button is admin-only and the message carries no secret, so it is returned to the
                // operator as the diagnostic they asked for; the stack trace is kept server-side. A
                // RuntimeException with no message (a bare NPE) would render as "Connection failed: null",
                // so fall back to a generic line and let the logged trace carry the detail.
                LOGGER.log(Level.WARNING, e, () -> "XCP-ng test connection to " + url + " failed");
                String detail = e.getMessage();
                return detail == null || detail.isBlank()
                        ? FormValidation.error("Connection failed; see the system log for details.")
                        : FormValidation.error("Connection failed: " + detail);
            }
        }
    }
}
