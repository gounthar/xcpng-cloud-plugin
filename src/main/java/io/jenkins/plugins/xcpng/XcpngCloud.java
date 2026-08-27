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
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.xcpng.client.CertificateFingerprint;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.ProvisionSpec;
import io.jenkins.plugins.xcpng.client.VmRef;
import io.jenkins.plugins.xcpng.client.XapiClient;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpAgentReceiver;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
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
 * ID of the XAPI username/password credential, the pinned certificate fingerprint if the pool needs one, an
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
     * How long a slot stays committed to an agent Jenkins has not registered yet. Generous on purpose: the
     * normal release is by name against the node list, one provisioning round later, so this deadline only
     * ever fires for a planned node core dropped without registering it. Expiring a live commitment early is
     * the failure the reservations exist to prevent; expiring a dead one late costs one delayed provision.
     */
    private static final long RESERVATION_TTL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Dedicated pool for warm-spare registrations. The clone, the start and the online wait moved to
     * {@link XcpngLauncher}, which core runs on its own remoting pool, so what is left here is short: build
     * the node and register it. It still runs off the caller's thread, because {@link #reconcileWarmPool}
     * holds this cloud's monitor while it fills the pool and {@code addNode} takes the queue lock, and
     * because a maintainer tick must not wait on a registration. Cached and daemon-threaded.
     */
    private static final ExecutorService PROVISION_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "xcpng-provisioner");
        thread.setDaemon(true);
        return thread;
    });

    private final String poolUrl;
    private final String credentialsId;
    /**
     * SHA-256 fingerprint of the certificate this pool is expected to present, or null for ordinary
     * verification against the JVM trust store. A stock XCP-ng pool is self-signed, so this is how such a
     * pool is reached; there is no longer any setting that accepts an unrecognised certificate.
     */
    @CheckForNull
    private final String certificateFingerprint;

    /**
     * The switch this field replaced, kept so an old configuration can be recognised and reported rather
     * than rejected. Never read for a trust decision: a cloud that arrives with this set and no fingerprint
     * is refused, not quietly downgraded. Written by exactly two things, and the second is the whole reason
     * this is not simply deleted -- XStream, loading an old {@code config.xml}, and
     * {@link #setTrustSelfSigned}, binding an old configuration-as-code document.
     *
     * @deprecated superseded by {@link #certificateFingerprint}; present for migration diagnostics only.
     */
    @Deprecated
    private boolean trustSelfSigned;
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
     * Where a pre-#149 {@code config.xml} kept the leaked-VM set, read on the way in and never written again.
     *
     * <p>The set now lives in {@link XcpngLeakedVmStore}, off the cloud object, because a cloud is rebuilt
     * through its {@code @DataBoundConstructor} on every configuration path but an XStream reload -- both a
     * configuration-as-code reload and an ordinary UI save of the cloud hand back a fresh object, and any
     * non-configuration field on it starts empty. That class carries the reasoning.
     *
     * <p>This field survives only so that a controller upgrading into #149 does not lose what it had
     * recorded. It has no initializer, so a cloud built by either constructor leaves it null and XStream
     * omits it from the config it writes; {@link #readResolve} hands whatever an older config carried to
     * {@link XcpngLeakedVmStore#deferMigration} and clears it.
     */
    private Set<String> leakedVmRefs;

    /**
     * How a live client is opened. Null in production, where {@link #openClient()} builds an
     * {@link XapiClient} from the configured credentials; a test injects an in-memory fake here.
     * Transient: it is behaviour, not configuration, and must never be persisted to {@code config.xml}.
     */
    private transient HypervisorClientFactory clientFactory;

    /**
     * Agents this cloud has committed to but Jenkins has not registered yet, keyed by node name.
     *
     * <p>A planned node is now handed back with its future already settled and no VM behind it, so core
     * registers it on its next provisioning round rather than this cloud registering it on a background
     * thread. That leaves a window of one round in which the agent counts against nothing: it is not in
     * {@code Jenkins.getNodes()}, and there is no in-flight task to ask. A concurrent round -- another
     * label's {@code NodeProvisioner}, or a warm-pool tick -- would plan straight past {@link #maxInstances}
     * into that window. These reservations close it, and they are why this is a map of node names rather
     * than the counter it replaced: an entry is dropped when its node turns up registered, which is a name
     * comparison against the node list and needs no event to fire and nothing to remember to call.
     *
     * <p>Transient and never persisted; the field initializer covers a fresh instance and {@link
     * #readResolve} covers a deserialized one (XStream skips both the constructor and field initializers).
     */
    private transient ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();

    /**
     * Executor for provisioning submits. Null in production, where {@link #provisionExecutor()} falls
     * back to the dedicated {@link #PROVISION_POOL}; a test injects a controllable one. Transient:
     * behaviour, never persisted to {@code config.xml}.
     */
    private transient ExecutorService provisionExecutor;

    /**
     * Guards the triggered-reconcile scheduling state below. A dedicated monitor rather than the cloud's own:
     * a pass holds the cloud monitor for its whole duration, so scheduling decisions taken on it would
     * serialize behind the very pass they are about. Never held while calling {@link #reconcileWarmPool}, so
     * it cannot participate in a lock cycle. Transient; {@link #readResolve} recreates it.
     */
    private transient Object reconcileLock = new Object();

    /**
     * Whether a triggered pass is in flight, and whether one more was asked for while it ran. Together they
     * bound the work a burst of teardowns can create at one running pass plus at most one follow-up, however
     * many triggers arrive. Guarded by {@link #reconcileLock}; transient, so a reloaded cloud starts idle.
     */
    private transient boolean reconcilePassRunning;

    private transient boolean reconcileRerunRequested;

    /**
     * Executor for triggered warm-pool reconciles. Null in production, where {@link #reconcileExecutor()}
     * falls back to {@code Computer.threadPoolForRemoting}: a reconcile is bookkeeping plus non-blocking
     * clone submits, so it does not warrant the provisioning pool's long-wait threads. A test injects a
     * controllable one. Transient: behaviour, never persisted.
     */
    private transient ExecutorService reconcileExecutor;

    /**
     * Executor for warm-pool drains. Null in production, where {@link #reapExecutor()} falls back to
     * {@code Computer.threadPoolForRemoting} (the same pool {@link XcpngRetentionStrategy} reaps on: a
     * teardown is a short blocking call, unlike a provision's minutes-long online wait, so it does not
     * warrant a pool of its own). A test injects a controllable one. Transient: behaviour, never persisted.
     */
    private transient ExecutorService reapExecutor;

    /**
     * Whether a provisioning task registers the node and waits for it to come online before completing
     * the planned-node future (the production behaviour, which stops the NodeProvisioner over-provisioning).
     * A test flips this off so the fake, never-connecting agents complete immediately. Transient; the
     * field initializer covers a fresh instance and {@link #readResolve} a deserialized one.
     */
    private transient boolean waitForOnline = true;

    /**
     * How long a provisioning task waits for its agent to come online, and how often it polls, before
     * giving up and tearing the VM down. Default to the production {@link #ONLINE_TIMEOUT_MINUTES} and
     * {@link #ONLINE_POLL_MILLIS}; a test shrinks them via {@link #setOnlineWait} so the timeout path
     * runs in milliseconds rather than five minutes. Transient: behaviour, never persisted; {@link
     * #readResolve} restores the defaults on deserialization, where XStream skips the field initializers.
     */
    private transient long onlineTimeoutMillis = TimeUnit.MINUTES.toMillis(ONLINE_TIMEOUT_MINUTES);

    private transient long onlinePollMillis = ONLINE_POLL_MILLIS;

    @DataBoundConstructor
    public XcpngCloud(
            @NonNull String name,
            String poolUrl,
            String credentialsId,
            String certificateFingerprint,
            int maxInstances,
            List<XcpngTemplate> templates) {
        super(name);
        // Trim on the way in so the persisted value matches what the validator parses; a stray space
        // would otherwise validate in the form yet break URI.create when the endpoint is built.
        this.poolUrl = poolUrl == null ? null : poolUrl.trim();
        this.credentialsId = credentialsId;
        // Normalise here so what is persisted is what every handshake compares against, whatever
        // punctuation the operator pasted. An unparseable value is rejected by doCheckCertificateFingerprint
        // on the form; reaching here with one (JCasC, a hand-edited config.xml) leaves it unset rather than
        // half-applied, and the connection then fails closed against the pool's real certificate.
        this.certificateFingerprint = normalizeOrNull(certificateFingerprint);
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
        if (reservations == null) {
            reservations = new ConcurrentHashMap<>();
        }
        if (reconcileLock == null) {
            reconcileLock = new Object();
        }
        // A pre-#149 config carries the leaked-VM set on the cloud itself. Hand it to the store now, while
        // this object is still the one holding it: configuration-as-code may replace the cloud before
        // anything asks it for its leaks, and a migration that waited for that question would never be asked
        // it. deferMigration touches no Jenkins API, which readResolve cannot do at this point in startup.
        if (leakedVmRefs != null) {
            XcpngLeakedVmStore.deferMigration(name, leakedVmRefs);
            leakedVmRefs = null;
        }
        // Transient boolean: XStream skips the field initializer, so a reloaded cloud would default to
        // false (fast-complete) and over-provision. Restore the production behaviour.
        waitForOnline = true;
        // Same reason: a reloaded cloud would deserialize these transient longs to 0, which would make
        // awaitOnline poll without sleeping and time out instantly. Restore the production wait.
        onlineTimeoutMillis = TimeUnit.MINUTES.toMillis(ONLINE_TIMEOUT_MINUTES);
        onlinePollMillis = ONLINE_POLL_MILLIS;
        // A cloud saved before pinning existed carries trustSelfSigned and no fingerprint. There is no
        // longer a code path that accepts an unrecognised certificate, so this cannot be honoured; say so
        // at load, loudly and by name, rather than letting the operator discover it as an opaque handshake
        // failure the first time a build queues. The connection now fails closed, which is the point.
        warnIfConfiguredWithTheRemovedTrustOption();
        return this;
    }

    /**
     * Present so configuration-as-code can find the retired attribute at all, and for no other purpose.
     *
     * <p>JCasC discovers attributes from <em>getters</em> and then resolves a setter for each one:
     * {@code BaseConfigurator} looks up {@code findGetter}, and skips the property outright when there is
     * none. A {@code @DataBoundSetter} on its own is therefore invisible to it, which is exactly the state
     * this fix was first written in, and the document still failed with {@code UnknownAttributesException}.
     *
     * <p>It reports {@code false} unconditionally, which is deliberate and is the whole reason it can exist
     * safely. {@code DataBoundConfigurator} compares each value against a freshly constructed default before
     * writing it out and omits anything equal to it, so a getter that answered {@code true} for a legacy
     * cloud would put {@code trustSelfSigned: true} back into every document exported from that controller,
     * resurrecting a dead key in configuration that outlives this migration. Only the export path reads this
     * method; the migration logic reads the field directly, so reporting the neutral value costs nothing and
     * cannot mask a real setting. {@code exportOmitsTheRetiredKeyAfterALegacyImport} is the guard.
     *
     * <p>This getter, not the {@code @DataBoundSetter} beside it, is the load-bearing half. Mutation-checked
     * both ways: removing the annotation from the setter changes nothing, because JCasC pairs a getter with
     * any conventionally named setter, while renaming this getter puts both migration tests straight back
     * to {@code UnknownAttributesException}. The annotation is kept as the conventional marker, not because
     * the binding needs it.
     *
     * <p>Deliberately <em>not</em> annotated {@code @Deprecated}, which is counter-intuitive enough to
     * need saying. JCasC reads that annotation off the setter and marks the attribute deprecated, and
     * {@code ConfigurationContext} defaults its deprecation policy to {@code reject} rather than
     * {@code warn}. Marking this pair deprecated therefore turns a document carrying the old key back into
     * a hard failure, which is the exact behaviour this change exists to remove. Nothing in JCasC overrides
     * that default, so it is not a test-harness artefact. The annotation is left off both accessors on
     * purpose; read {@link #getCertificateFingerprint} to find out how this cloud verifies the pool.
     */
    public boolean isTrustSelfSigned() {
        return false;
    }

    /**
     * Bind, and refuse, the option {@link #certificateFingerprint} replaced. This is where a
     * configuration-as-code document carrying the retired key lands.
     *
     * <p>Not annotated {@code @Deprecated}, for the reason given on {@link #isTrustSelfSigned}: that
     * annotation on the setter is exactly what JCasC reads to mark the attribute deprecated, and its
     * default policy rejects deprecated attributes rather than warning about them. Annotating it would
     * restore the hard failure this whole change exists to remove.
     *
     * <p>The value is recorded and never honoured. Set a {@code certificateFingerprint} instead.
     */
    @DataBoundSetter
    public void setTrustSelfSigned(boolean trustSelfSigned) {
        this.trustSelfSigned = trustSelfSigned;
        warnIfConfiguredWithTheRemovedTrustOption();
    }

    /**
     * Say, once and by name, that this cloud is configured with an option that no longer does anything.
     *
     * <p>Shared by {@link #readResolve} and {@link #setTrustSelfSigned} so the two migration routes cannot
     * drift in what they tell the operator. The advice covers both, because the fix differs: a UI-managed
     * cloud is repaired through the form, and a configuration-as-code one has to be repaired in the YAML,
     * where an edit through the form would be overwritten on the next reload.
     */
    private void warnIfConfiguredWithTheRemovedTrustOption() {
        if (trustSelfSigned && certificateFingerprint == null) {
            LOGGER.warning("Cloud '" + name + "' is configured with the removed \"Trust self-signed"
                    + " certificate\" option, which accepted any certificate from any host. It has no"
                    + " replacement and is ignored. This cloud will not connect until its certificate"
                    + " fingerprint is set: in the UI, open the cloud and press Test connection to read the"
                    + " fingerprint the pool presents, then save it in the Certificate fingerprint field;"
                    + " under configuration-as-code, replace trustSelfSigned with certificateFingerprint in"
                    + " the YAML.");
        }
    }

    public String getPoolUrl() {
        return poolUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @CheckForNull
    public String getCertificateFingerprint() {
        return certificateFingerprint;
    }

    /**
     * The canonical form of {@code raw}, or null if it is absent or unparseable. Null rather than a
     * throw, because the two callers -- the constructor and JCasC binding -- must not fail a whole
     * controller boot over one malformed field; failing closed at connect time is the safer half.
     */
    @CheckForNull
    private static String normalizeOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CertificateFingerprint.normalize(raw);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Ignoring an unparseable certificate fingerprint: " + e.getMessage());
            return null;
        }
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
        // Subtract what this cloud is already building for this template. Core will not do it for us, and
        // that is the whole reason this line exists rather than being left to the NodeProvisioner:
        //
        //   - Planned capacity counts a PlannedNode only while its future is NOT done
        //     (NodeProvisioner.java:298, 2.555.3). Ours settles inside provision(), so it is never counted,
        //     not even for the round that harvests it. The term is 0 from the moment we return.
        //   - Connecting capacity is min(EMA, snapshot). The snapshot is right immediately, but the EMA
        //     needs a tick or two of the 10s LoadStatistics clock to climb, and the min throttles the pair
        //     to the EMA.
        //
        // So for roughly one round after a node registers, both of core's terms read about zero while the
        // agent is demonstrably booting, and a queue whose own EMA has climbed past the threshold gets a
        // second agent for the same build. Measured on the lab pool on 2026-08-27: one queued build, two
        // clones, from provisioning rounds ten seconds apart. The old code was covered by accident, because
        // awaitOnline kept the planned node's future pending and so kept the planned term at 1 for the
        // whole boot.
        int remaining = excessWorkload - alreadyBuilding(template);
        // One VM per planned node, and one executor per VM, so each planned node serves exactly one unit of
        // the excess workload. Stop when the workload is met or the instance cap is reached, whichever comes
        // first. Throughput scales by cloning more agents, never by stacking executors onto one: see
        // XcpngAgent.EXECUTORS_PER_AGENT for why the two cannot coexist.
        while (remaining > 0 && planned.size() < capacity) {
            // A random suffix, not a counter: an in-process counter resets on controller restart, so a
            // crash that leaves an old node behind could hand a new clone the same name and collide in
            // the pool. A UUID segment stays unique across restarts.
            final String displayName = "xcpng-" + template.getTemplateName() + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
            // The cloud-stats activity id for this provisioning. One instance is created here and shared:
            // the TrackedPlannedNode below and the XcpngAgent built by createAgent both carry this exact
            // object. cloud-stats' Id equality is per-instance (a random fingerprint, not the names), so a
            // rebuilt id would never correlate; the phases stay on one activity only by sharing this handle.
            final ProvisioningActivity.Id activityId =
                    new ProvisioningActivity.Id(name, template.getTemplateName(), displayName);
            // On-demand agents are never warm spares: they are provisioned for queued work. The node is
            // handed back with no VM behind it yet; XcpngLauncher builds that when core connects the
            // computer, which is what keeps the registration in core's hands and out of this cloud's.
            XcpngAgent agent;
            try {
                agent = createAgent(template, displayName, activityId, false);
            } catch (Descriptor.FormException | IOException e) {
                // Building the node object is local work with no VM behind it, so this is a
                // misconfiguration (a label expression the template rejects, most likely) rather than a
                // pool problem. Nothing was reserved and nothing was cloned; stop planning this round
                // rather than spinning on the same failure for every unit of the excess workload.
                LOGGER.log(Level.WARNING, e, () -> "Could not plan agent " + displayName + " for cloud " + name);
                break;
            }
            // Reserve before handing the planned node out, so a concurrent round sees the commitment even
            // though the node will not be registered until core polls this future.
            reserve(displayName, template.getTemplateName(), false);
            planned.add(new TrackedPlannedNode(
                    activityId, XcpngAgent.EXECUTORS_PER_AGENT, CompletableFuture.completedFuture(agent)));
            remaining -= XcpngAgent.EXECUTORS_PER_AGENT;
        }
        return planned;
    }

    /**
     * Build the node object for one agent, with no VM behind it. The clone, the seed and the start happen
     * later, in {@link XcpngLauncher}, once Jenkins has registered this node and starts connecting its
     * computer.
     *
     * <p>Package-visible so a test can build an agent without going through a provisioning round. The
     * {@code activityId} is the same instance carried by the planned node, stamped onto the agent so
     * cloud-stats keeps both on one activity.
     */
    @NonNull
    XcpngAgent createAgent(
            @NonNull XcpngTemplate template,
            @NonNull String displayName,
            @NonNull ProvisioningActivity.Id activityId,
            boolean warm)
            throws Descriptor.FormException, IOException {
        // The cloud itself, not just its name: the agent snapshots this cloud's connection parameters so it
        // can still destroy its VM if the cloud is later deleted or renamed.
        return new XcpngAgent(displayName, this, template, idleMinutes, activityId, warm);
    }

    /**
     * Clone the template, start it, and record the clone on the agent. Called by {@link XcpngLauncher} on the
     * thread core is connecting the computer on, so this cloud's own client, seed and leaked-VM handling stay
     * in one place rather than being duplicated into the launcher.
     *
     * <p>The reference is stamped onto the agent between the clone and the start, deliberately: everything
     * after the clone can fail, and a teardown can only destroy a handle it can see.
     */
    void provisionVm(@NonNull XcpngAgent agent, @NonNull XcpngTemplate template, @NonNull TaskListener listener)
            throws Exception {
        String displayName = agent.getNodeName();
        try (HypervisorClient client = openClient()) {
            VmRef templateRef = client.resolveTemplate(template.getTemplateName());
            ProvisionSpec spec = new ProvisionSpec(
                    displayName,
                    template.getNumCpus(),
                    template.getMemoryBytes(),
                    null,
                    null,
                    null,
                    seedFor(displayName, template),
                    // Stamp the owning cloud onto the VM record. The plugin's own teardown is the normal
                    // path; this is what lets tools/reaper.py find a clone the plugin lost track of, after
                    // a crash mid-provision or a destroy that threw.
                    name);
            listener.getLogger().println("Cloning XCP-ng template " + template.getTemplateName() + " for this agent.");
            VmRef clone = client.cloneFromTemplate(templateRef, spec);
            agent.setVmRef(clone.value());
            client.start(clone);
            listener.getLogger().println("Started XCP-ng VM " + clone.value() + "; waiting for the agent to connect.");
            LOGGER.log(Level.INFO, () -> "Provisioned XCP-ng VM " + clone.value() + " as agent " + displayName);
        }
    }

    /**
     * Register one pre-booted warm spare: reserve a slot, build the node, and add it to Jenkins. The VM
     * itself is built by {@link XcpngLauncher} when core connects the new computer, exactly as for an
     * on-demand agent -- {@code AbstractCIBase.updateComputer} calls {@code retentionStrategy.start} on a
     * freshly added node, and {@code CloudRetentionStrategy.start} is an unguarded {@code connect}.
     *
     * <p>Returns false if the pool rejected the submit, which it does only while shutting down, having
     * already released the reservation; the caller stops filling for this tick.
     *
     * <p>A warm spare bypasses the {@code NodeProvisioner} altogether, so nothing files its cloud-stats
     * activity for it. This opens one by hand with {@code onStarted} before the node is added, so an activity
     * exists by the time the spare connects or is drained -- otherwise cloud-stats' own online and deletion
     * listeners call {@code getActivityFor} on an untracked node and throw. Only a failure <em>before</em> the
     * node is registered is closed here, with {@code onFailure}: past that point a computer exists, and
     * cloud-stats' listeners (and {@link XcpngLauncher}, for a failed launch) carry the activity the rest of
     * the way.
     */
    private boolean launchWarmSpare(
            @NonNull XcpngTemplate template, @NonNull String displayName, @NonNull ProvisioningActivity.Id activityId) {
        reserve(displayName, template.getTemplateName(), true);
        try {
            provisionExecutor().submit(() -> {
                try {
                    CloudStatistics.ProvisioningListener.get().onStarted(activityId);
                    XcpngAgent spare = createAgent(template, displayName, activityId, true);
                    Jenkins.get().addNode(spare);
                } catch (Exception e) {
                    // Nothing was cloned -- the VM is the launcher's job and the launcher never ran -- so
                    // there is no VM to clean up here, only the reservation and the open activity.
                    release(displayName);
                    try {
                        CloudStatistics.ProvisioningListener.get().onFailure(activityId, e);
                    } catch (RuntimeException statsFailure) {
                        LOGGER.log(
                                Level.WARNING,
                                statsFailure,
                                () -> "Could not record the failed provision of " + displayName + " in cloud-stats");
                    }
                    LOGGER.log(Level.WARNING, e, () -> "Could not register warm spare " + displayName);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            release(displayName);
            LOGGER.log(Level.WARNING, e, () -> "Could not schedule provision of " + displayName);
            return false;
        }
    }

    /**
     * Bring each template's warm pool to its {@code minInstances} target, in both directions: launch
     * pre-booted spares to cover a deficit (bounded by the cloud's instance cap), then drain surplus spares
     * once a target is lowered or a template is removed. Synchronized on the same monitor as
     * {@link #provision} so warm and on-demand reservations cannot both snapshot the same headroom and
     * overshoot {@code maxInstances}. Called on a schedule by {@link XcpngWarmPoolMaintainer};
     * package-visible so a test can drive a reconcile directly.
     *
     * <p>The drain is what makes the target authoritative rather than a floor. Spares are exempt from the
     * idle reap by design (see {@link XcpngRetentionStrategy}), so without it a lowered target would leave
     * its surplus running until each spare happened to pick up a build.
     */
    synchronized void reconcileWarmPool() {
        // One pass over the node list feeds both halves: count this cloud's agents (for the cap) and bucket
        // its unused warm spares by template, rather than rescanning the list per template. The spares
        // themselves are collected, not just counted, because the drain below needs the agents to reap.
        Set<String> configuredTemplates = new HashSet<>();
        for (XcpngTemplate template : templates) {
            configuredTemplates.add(template.getTemplateName());
        }
        Set<String> registered = new HashSet<>();
        Map<String, List<XcpngAgent>> warmByTemplate = new HashMap<>();
        List<XcpngAgent> orphanedSpares = new ArrayList<>();
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof XcpngAgent agent && name.equals(agent.getCloudName())) {
                registered.add(agent.getNodeName());
                if (agent.isWarm()) {
                    ProvisioningActivity.Id id = agent.getId();
                    if (id != null) {
                        // A spare whose template no longer resolves belongs to a template the administrator
                        // removed: nothing will ever want it, so it is drained outright rather than counted
                        // against a target that no longer exists.
                        if (configuredTemplates.contains(id.getTemplateName())) {
                            warmByTemplate
                                    .computeIfAbsent(id.getTemplateName(), k -> new ArrayList<>())
                                    .add(agent);
                        } else {
                            orphanedSpares.add(agent);
                        }
                    }
                }
            }
        }
        // The same headroom formula as availableCapacity(), fed from the pass above and decremented
        // locally as launches are submitted (launchWarmSpare reserves too, but the local counter saves
        // re-reading it and keeps the two in step within this tick). Keep the formulas aligned. Spares
        // drained later in this tick still count as active here: their teardown is asynchronous, so they
        // are not headroom yet, and letting the next tick see the freed capacity is the honest reading.
        pruneReservations(registered);
        int capacity = Math.max(0, maxInstances - registered.size() - reservations.size());
        for (XcpngTemplate template : templates) {
            int target = warmTarget(template);
            if (target <= 0) {
                continue;
            }
            // Deficit against both the spares already registered and those still booting, so repeated
            // ticks do not stack duplicate provisions for the same template.
            int deficit = target
                    - warmCount(warmByTemplate, template.getTemplateName())
                    - warmReserved(template.getTemplateName());
            int toLaunch = Math.min(deficit, capacity);
            for (int i = 0; i < toLaunch; i++) {
                final String displayName = "xcpng-" + template.getTemplateName() + "-"
                        + UUID.randomUUID().toString().substring(0, 8);
                final ProvisioningActivity.Id activityId =
                        new ProvisioningActivity.Id(name, template.getTemplateName(), displayName);
                if (!launchWarmSpare(template, displayName, activityId)) {
                    // Pool shutting down; no point trying this or any later template this tick.
                    return;
                }
                capacity--;
            }
        }
        // Drain after the fill, in the same tick and under the same monitor. A template sitting at its
        // target has no deficit and no surplus, so it is untouched by both halves.
        for (XcpngTemplate template : templates) {
            List<XcpngAgent> spares = warmByTemplate.get(template.getTemplateName());
            if (spares == null) {
                continue;
            }
            int surplus = spares.size() - warmTarget(template);
            for (XcpngAgent spare : spares) {
                if (surplus <= 0) {
                    break;
                }
                if (drainSpare(spare)) {
                    surplus--;
                }
            }
        }
        for (XcpngAgent spare : orphanedSpares) {
            drainSpare(spare);
        }
    }

    /**
     * Ask for a warm-pool reconcile now, because a slot this cloud owns has just been freed. Called from the
     * teardown path in {@link XcpngRetentionStrategy#reap}, which is the moment the capacity a spare's
     * replacement needs actually comes back.
     *
     * <p>Without it the replacement waits for {@link XcpngWarmPoolMaintainer}'s next tick, and how long that
     * is depends on nothing but where the teardown happened to fall in a one-minute period. Measured against
     * the lab pool on the build-completion path with {@code maxInstances == minInstances}: 13.9s, 46.0s and
     * 46.6s, on top of about 32s of guest boot. A cloud with headroom escapes this only when a build outlasts
     * the recurrence period, so that a mid-build tick clones the replacement while the old agent still runs;
     * with builds shorter than the period the same wait comes back.
     *
     * <p>Debounced rather than fired per removal. A drain of several surplus spares, or a mass teardown, would
     * otherwise raise one reconcile each and every one of them would queue on this cloud's monitor behind the
     * first, each holding a thread of whatever pool runs them. Instead a trigger arriving while a pass is in
     * flight only sets {@link #reconcileRerunRequested}, and the running task loops once more when it finishes:
     * that trigger's freed slot may not have been visible to the pass already under way, so one follow-up is
     * owed, but only one however many triggers arrive.
     *
     * <p>The follow-up runs in the same task rather than as a fresh submission, which is what bounds the pool
     * usage at one thread. An earlier version cleared a single flag as the task's first statement and then
     * blocked on the cloud monitor; because {@code Computer.threadPoolForRemoting} is multi-threaded, every
     * later trigger passed the guard and took another worker with it. See
     * {@code triggersDuringARunningPassDoNotEachQueueTheirOwn}.
     *
     * <p>This method never takes the cloud monitor itself — it flags and submits, nothing more — and that is
     * what makes it safe to call from the reap path. {@link #reconcileWarmPool} holds this monitor while
     * {@link #drainSpare} calls into {@link XcpngRetentionStrategy}'s, so the lock order in this plugin is
     * cloud then agent strategy; a trigger that reconciled inline from a thread holding a strategy monitor
     * would take the two the other way round, and a concurrent drain makes that reachable rather than
     * theoretical. Running the pass on an executor also keeps a teardown from waiting on a provisioning round.
     */
    void requestReconcile() {
        // A cloud holding no warm pool has no deficit a freed slot could fill, so there is nothing to do and
        // no reason to take the monitor.
        if (!hasWarmPool()) {
            return;
        }
        synchronized (reconcileLock) {
            if (reconcilePassRunning) {
                // A pass is already in flight. Owe it one more run and take no thread of our own.
                reconcileRerunRequested = true;
                return;
            }
            reconcilePassRunning = true;
        }
        try {
            reconcileExecutor().submit(this::runReconcilePasses);
        } catch (RejectedExecutionException e) {
            // The pool refuses only while shutting down. Put the state back rather than leaving it claimed,
            // so a later trigger is not swallowed by a pass that will never run.
            synchronized (reconcileLock) {
                reconcilePassRunning = false;
                reconcileRerunRequested = false;
            }
            LOGGER.log(Level.FINE, e, () -> "Could not schedule a triggered warm-pool reconcile for cloud " + name);
        }
    }

    /**
     * Run reconcile passes until nothing more is owed. Called only from the task {@link #requestReconcile}
     * submits, and only ever by one thread at a time, because that submission is the sole transition into
     * {@link #reconcilePassRunning}.
     */
    private void runReconcilePasses() {
        while (true) {
            try {
                reconcileWarmPool();
            } catch (RuntimeException e) {
                // Mirrors XcpngWarmPoolMaintainer: a failed reconcile is logged rather than left to the
                // pool's uncaught handler, and the next tick tries again. A failure must not strand
                // reconcilePassRunning either, or no trigger would ever be honoured again.
                LOGGER.log(Level.WARNING, e, () -> "Triggered warm-pool reconcile failed for cloud " + name);
            }
            synchronized (reconcileLock) {
                if (!reconcileRerunRequested) {
                    reconcilePassRunning = false;
                    return;
                }
                reconcileRerunRequested = false;
            }
        }
    }

    /** Whether any template on this cloud wants warm spares at all. */
    private boolean hasWarmPool() {
        for (XcpngTemplate template : templates) {
            if (warmTarget(template) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many warm spares this template should be holding. The configured {@code minInstances}, floored at
     * zero, except that a template with no labels is worth none.
     *
     * <p>Agents are {@link XcpngAgent#USAGE_MODE} — {@code EXCLUSIVE} — and {@link #canProvision} declines a
     * null label, so a build reaches these agents only by naming a label the template carries. A template
     * carrying none matches nothing, and a spare cloned from it would sit warm until its cloud was
     * reconfigured, holding a slot against {@code maxInstances} that a template builds can actually reach
     * would otherwise have. {@link XcpngTemplate#readResolve} already warns at load that such a template
     * provisions nothing; the warm pool is the one path that would have gone on provisioning anyway, which
     * made that warning false rather than merely unheeded.
     *
     * <p>Returning zero rather than skipping the template also drains spares an earlier release, or an
     * administrator clearing the label field on a running controller, has already left behind: the drain
     * half measures surplus against this same number, so they go from "at target" to "all surplus".
     */
    private static int warmTarget(@NonNull XcpngTemplate template) {
        String labels = template.getLabelString();
        if (labels == null || labels.isBlank()) {
            return 0;
        }
        return Math.max(0, template.getMinInstances());
    }

    private static int warmCount(@NonNull Map<String, List<XcpngAgent>> warmByTemplate, @NonNull String templateName) {
        List<XcpngAgent> spares = warmByTemplate.get(templateName);
        return spares == null ? 0 : spares.size();
    }

    /**
     * Reap one surplus warm spare, off the reconcile thread. Returns whether the teardown was handed over, so
     * the caller only counts a spare against the surplus once it is actually going away.
     *
     * <p>The spare must still be idle: one that has just accepted a build is no longer a spare at all (it is
     * a used, single-use agent mid-build), and yanking it would kill that build. A node whose computer does
     * not exist yet is left to the next tick.
     *
     * <p>The teardown itself is delegated to the agent's own {@link XcpngRetentionStrategy}, rather than
     * reimplemented here, so both routes to reclaiming a spare share one monitor and one in-flight guard. A
     * warm spare that never came online keeps no idle exemption, so the retention thread can be reaping the
     * very spare this tick just picked as surplus; going through the strategy makes one of the two back off
     * instead of both firing {@code destroyWithDisks} at the same VM. The strategy also owns the window
     * between the idle check above and the destroy: it refuses further tasks, then re-reads the computer
     * under the queue lock and abandons the teardown if a build got in first.
     *
     * <p>The executor is passed down because {@code destroyWithDisks} is a blocking network call and this runs
     * while holding the cloud's monitor: a slow or hanging pool must not stall the maintainer tick, nor block
     * a concurrent {@link #provision} round waiting on the same lock.
     */
    private boolean drainSpare(@NonNull XcpngAgent spare) {
        // getComputer(), not toComputer(): the reap seam is typed to the agent's cloud computer.
        SlaveComputer computer = spare.getComputer();
        if (!(computer instanceof AbstractCloudComputer<?> cloudComputer) || !computer.isIdle()) {
            return false;
        }
        if (!(computer.getRetentionStrategy() instanceof XcpngRetentionStrategy strategy)) {
            return false;
        }
        LOGGER.log(Level.FINE, () -> "Draining surplus XCP-ng warm spare " + spare.getNodeName());
        strategy.reap(cloudComputer, reapExecutor());
        return true;
    }

    /**
     * Record a VM the plugin failed to destroy, so it is reclaimed later rather than leaked. Called from the
     * one {@code destroyWithDisks} failure path there now is, {@link XcpngAgent#_terminate}, which every
     * teardown converges on -- including the one {@link XcpngLauncher} runs when a launch fails after the
     * clone. This set then holds the last reference to the VM. Persisted at once so the ref survives a
     * restart; a duplicate ref is a no-op. {@link #sweepLeakedVms} reissues the destroy.
     */
    void recordLeakedVm(@NonNull String vmRef) {
        // The store persists itself, and reports whether the ref was new so a duplicate record is silent.
        if (XcpngLeakedVmStore.get().record(name, vmRef)) {
            LOGGER.log(Level.FINE, () -> "Recorded leaked XCP-ng VM " + vmRef + " for cloud " + name);
        }
    }

    /**
     * Reissue the destroy for every VM a past teardown failed to remove, dropping each ref that now destroys
     * cleanly and leaving the rest for the next tick. Called on a schedule by {@link XcpngWarmPoolMaintainer};
     * package-visible so a test can drive it directly. Opens at most one client per sweep, and none at all when
     * nothing is recorded, so a healthy cloud pays nothing for it. Runs off the cloud monitor: the blocking
     * destroy must not stall a concurrent {@link #provision} or {@link #reconcileWarmPool}, and the leaked set
     * carries its own lock.
     */
    void sweepLeakedVms() {
        // A snapshot off the store: iterating it cannot throw even if a record lands mid-sweep.
        List<String> pending = new ArrayList<>(XcpngLeakedVmStore.get().refs(name));
        if (pending.isEmpty()) {
            return;
        }
        List<String> reclaimed = new ArrayList<>();
        try (HypervisorClient client = openClient()) {
            for (String vmRef : pending) {
                try {
                    client.destroyWithDisks(new VmRef(vmRef));
                    reclaimed.add(vmRef);
                    LOGGER.log(Level.INFO, () -> "Reclaimed leaked XCP-ng VM " + vmRef + " for cloud " + name);
                } catch (RuntimeException e) {
                    // Still unreachable. Keep it recorded and retry next tick; logged at FINE so a
                    // persistently-stuck ref does not spam the log every minute (the leak itself was SEVERE).
                    LOGGER.log(Level.FINE, e, () -> "Leaked XCP-ng VM " + vmRef + " still could not be destroyed");
                }
            }
        } catch (RuntimeException e) {
            // Could not even open a session (bad credentials, pool down). Whatever was reclaimed before the
            // failure is still dropped in the finally; the rest stay recorded for the next tick.
            LOGGER.log(Level.FINE, e, () -> "Could not open a client to sweep leaked XCP-ng VMs for cloud " + name);
        } finally {
            // In a finally, not after the try, so a client whose close() throws cannot resurrect a ref whose
            // destroy already succeeded. The store drops and persists them in one step.
            XcpngLeakedVmStore.get().drop(name, reclaimed);
        }
    }

    /** A snapshot of the VM refs a past teardown could not destroy, for asserting the durable orphan set in tests. */
    @NonNull
    Set<String> leakedVmRefs() {
        // Already a defensive copy on the way out of the store; the snapshot iteration cannot throw a CME.
        return XcpngLeakedVmStore.get().refs(name);
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

    /**
     * Executor that runs warm-pool drains. Production reaps on {@code Computer.threadPoolForRemoting}; a
     * test injects one via {@link #setReapExecutor} so a drain is observable by the time it asserts.
     */
    @NonNull
    private ExecutorService reapExecutor() {
        return reapExecutor != null ? reapExecutor : Computer.threadPoolForRemoting;
    }

    /** Test seam: run warm-pool drains on a controllable executor. */
    void setReapExecutor(ExecutorService reapExecutor) {
        this.reapExecutor = reapExecutor;
    }

    /**
     * Executor that runs triggered warm-pool reconciles. Production shares {@code Computer.threadPoolForRemoting}
     * with the drains; a test injects one via {@link #setReconcileExecutor} so a trigger's pass has run by the
     * time it asserts.
     */
    @NonNull
    private ExecutorService reconcileExecutor() {
        return reconcileExecutor != null ? reconcileExecutor : Computer.threadPoolForRemoting;
    }

    /** Test seam: run triggered warm-pool reconciles on a controllable executor. */
    void setReconcileExecutor(ExecutorService reconcileExecutor) {
        this.reconcileExecutor = reconcileExecutor;
    }

    /** Test seam: slots committed to agents Jenkins has not registered yet, for asserting the cap accounting. */
    int inFlightCount() {
        Set<String> registered = registeredAgentNames();
        pruneReservations(registered);
        return reservations.size();
    }

    /**
     * Test seam: with a fake client the provisioned agents never connect, so the online wait would block
     * for the whole timeout. Turning it off makes {@link XcpngLauncher} return as soon as the VM is
     * "cloned", which is what the capacity/planning tests assert against. Core then reports the launch as
     * failed, because no channel ever appeared, and leaves the node registered and offline -- which is what
     * those tests want to look at.
     */
    void setWaitForOnline(boolean waitForOnline) {
        this.waitForOnline = waitForOnline;
    }

    /**
     * Test seam: shrink the online wait and poll interval so a never-connecting fake agent is given up
     * on in milliseconds, exercising the timeout-and-teardown path without the production five-minute wait.
     */
    void setOnlineWait(long timeoutMillis, long pollMillis) {
        this.onlineTimeoutMillis = timeoutMillis;
        this.onlinePollMillis = pollMillis;
    }

    /**
     * Block until the agent's computer connects, or fail after {@link #onlineTimeoutMillis} (the production
     * {@link #ONLINE_TIMEOUT_MINUTES} unless a test shrank it). Called by {@link XcpngLauncher}, which turns
     * the failure into a failed launch and tears the VM down.
     *
     * <p>Still lives on the cloud rather than the launcher, for one reason: the timeout and poll interval are
     * injectable seams, and every test that drives a provisioning round needs them. Waiting in the launcher
     * is what keeps {@code SlaveComputer.isConnecting()} true for the whole boot window, which is how core's
     * own {@code connectingExecutors} accounting sees a booting agent.
     *
     * <p>Returns immediately when {@link #waitForOnline} is off, which is a test seam and nothing else.
     */
    void awaitOnline(@NonNull SlaveComputer computer, @NonNull String displayName) throws InterruptedException {
        if (!waitForOnline) {
            return;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(onlineTimeoutMillis);
        while (!computer.isOnline()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException(
                        "agent " + displayName + " did not come online within " + onlineTimeoutMillis + " ms");
            }
            Thread.sleep(onlinePollMillis);
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
        return openClient(poolUrl, credentialsId, certificateFingerprint, "cloud '" + name + "'");
    }

    /**
     * Build a live {@link XapiClient} from a set of connection parameters, resolving the credential from
     * the store at this moment rather than from anything persisted. Static and parameterised because
     * {@link XcpngAgent#_terminate} needs the same construction from its own connection snapshot when the
     * cloud that provisioned it has been deleted or renamed and there is no instance left to ask; keeping
     * one implementation means the two paths cannot drift on TLS trust or credential scoping.
     *
     * @param owner what the parameters belong to, for the message thrown when the credential is gone.
     */
    @NonNull
    static HypervisorClient openClient(
            @CheckForNull String poolUrl,
            @CheckForNull String credentialsId,
            @CheckForNull String certificateFingerprint,
            @NonNull String owner) {
        StandardUsernamePasswordCredentials credentials = DescriptorImpl.lookupCredentials(poolUrl, credentialsId);
        if (credentials == null) {
            throw new IllegalStateException("No XAPI credentials configured for " + owner + ".");
        }
        return new XapiClient(
                poolUrl, credentials.getUsername(), credentials.getPassword().getPlainText(), certificateFingerprint);
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

    /**
     * Whether {@code template}'s labels satisfy {@code label}.
     *
     * <p>A null label is a job with no label constraint, and this cloud declines it. That is the
     * provisioning half of {@link XcpngAgent#USAGE_MODE}: the agents are {@code EXCLUSIVE}, so a build
     * with no label expression can never be scheduled onto one. Core asks anyway — {@code NodeProvisioner}
     * passes a null label to {@link #canProvision} on behalf of the unlabeled queue — so answering yes
     * here would clone a VM for a build that cannot use it, and, because {@code UnlabeledLoadStatistics}
     * counts only {@code NORMAL} nodes, the demand it was cloned for never looks satisfied.
     */
    private static boolean labelMatches(@CheckForNull Label label, XcpngTemplate template) {
        return label != null && label.matches(Label.parse(template.getLabelString()));
    }

    /**
     * Instance-cap headroom: the configured maximum minus the agents <em>this</em> cloud already runs.
     * Filtered by cloud name so one XCP-ng cloud does not throttle another that shares the controller.
     */
    private int availableCapacity() {
        Set<String> registered = registeredAgentNames();
        // Subtract reservations too: they will become nodes but have not yet, so counting only registered
        // agents would let a concurrent round provision past the cap.
        pruneReservations(registered);
        return Math.max(0, maxInstances - registered.size() - reservations.size());
    }

    /**
     * Units of work this cloud has already committed to for a template and has not yet delivered: agents
     * planned but not registered, and agents registered but still being built by {@link XcpngLauncher}.
     *
     * <p>An agent that is online is deliberately not counted. Core sees it, in {@code availableExecutors},
     * and it can take the build itself; counting it here as well would stop this cloud growing at all.
     */
    private int alreadyBuilding(@NonNull XcpngTemplate template) {
        Set<String> registered = registeredAgentNames();
        pruneReservations(registered);
        int building = 0;
        for (Reservation reservation : reservations.values()) {
            if (reservation.templateName().equals(template.getTemplateName())) {
                building++;
            }
        }
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof XcpngAgent agent && name.equals(agent.getCloudName())) {
                // Read the id once and branch on the local, as reconcileWarmPool does: it is @CheckForNull,
                // and two calls are two chances for it to be null.
                ProvisioningActivity.Id id = agent.getId();
                if (id != null && template.getTemplateName().equals(id.getTemplateName()) && isBeingBuilt(agent)) {
                    building++;
                }
            }
        }
        return building;
    }

    /**
     * Whether this agent is registered and on its way up: its computer has not been created yet, or its
     * launcher is still running. An agent whose launch failed is neither, so a failed provision does not
     * suppress its own replacement.
     */
    private static boolean isBeingBuilt(@NonNull XcpngAgent agent) {
        Computer computer = agent.toComputer();
        if (computer == null) {
            return true;
        }
        return computer instanceof SlaveComputer slave && slave.isConnecting();
    }

    /** Node names of the agents this cloud currently has registered with Jenkins. */
    @NonNull
    private Set<String> registeredAgentNames() {
        Set<String> registered = new HashSet<>();
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof XcpngAgent agent && name.equals(agent.getCloudName())) {
                registered.add(agent.getNodeName());
            }
        }
        return registered;
    }

    /**
     * Hand a reservation over to the node it was taken for. Called by {@link XcpngLauncher} the moment it
     * runs, which is the first point at which the node is certainly registered -- a computer exists for it,
     * and nothing but a registered node has one. From here the node itself is what {@link #availableCapacity}
     * counts, so holding the reservation any longer would count the agent twice.
     *
     * <p>A capacity read would drop it too, by name against the node list, but only if one happened while the
     * node was still there. A launch that fails tears the node down within seconds, so without this the
     * reservation would outlive its node and wedge a slot until its deadline.
     */
    void noteRegistered(@NonNull String nodeName) {
        release(nodeName);
    }

    /** Commit a slot to an agent that does not exist as a node yet. */
    private void reserve(@NonNull String nodeName, @NonNull String templateName, boolean warm) {
        reservations.put(
                nodeName, new Reservation(templateName, warm, System.currentTimeMillis() + RESERVATION_TTL_MILLIS));
    }

    /**
     * Give a slot back: to the node it was taken for, or because the commitment will never become one.
     *
     * <p>Synchronized on the cloud monitor, which is the same monitor {@link #provision} and
     * {@link #reconcileWarmPool} hold for a whole pass, and that is the point rather than habit. A pass
     * reads the registered node list and the reservations at two different instants. Without this, a
     * release landing between those two reads makes an agent invisible to both: the node list was
     * snapshotted before it registered, and its reservation is gone by the time the reservations are
     * counted. The pass then plans one more agent than the cap allows.
     *
     * <p>Taking the monitor here rules that out rather than narrowing it. A release cannot happen while a
     * pass is running, so either the pass sees the reservation, or the release already happened, which means
     * the node was registered before the pass started and is therefore in its snapshot. Registration
     * strictly precedes the launcher that calls {@link #noteRegistered}, which is what makes that second
     * branch hold.
     *
     * <p>No new lock order: this takes the cloud monitor and nothing else, and the launcher calls it before
     * it holds anything. The warm-launch failure path already calls it from inside a pass, and Java monitors
     * are re-entrant.
     */
    private synchronized void release(@NonNull String nodeName) {
        reservations.remove(nodeName);
    }

    /**
     * Drop reservations that have done their job, or that never will.
     *
     * <p>The first case is the normal one and needs no event: once the node is in {@code registered} it is
     * counted as an agent, and leaving the reservation would count it twice.
     *
     * <p>The deadline is the backstop for the second case -- a planned node core dropped without ever
     * registering it, which no callback tells us about. It is set far longer than the round or two a
     * registration takes, because expiring a live commitment early is the failure this whole mechanism
     * exists to prevent, while expiring a dead one late costs one delayed provision.
     */
    private void pruneReservations(@NonNull Set<String> registered) {
        long now = System.currentTimeMillis();
        reservations.entrySet().removeIf(entry -> {
            if (registered.contains(entry.getKey())) {
                return true;
            }
            if (entry.getValue().expiresAt() > now) {
                return false;
            }
            // Said out loud, because this is the one branch that gives a slot back to a plan that may still
            // be live. It should not happen: core registers a planned node on its next round, and the
            // launcher hands the slot over before that node runs anything. If these lines turn up in a log
            // beside a cloud running over its cap, the deadline is the first thing to look at.
            LOGGER.log(
                    Level.INFO,
                    () -> "Reservation for " + entry.getKey() + " on cloud " + name + " expired after "
                            + RESERVATION_TTL_MILLIS + " ms without the node being registered; releasing the slot");
            return true;
        });
    }

    /** Reservations for a template's warm spares, so repeated ticks do not stack duplicate provisions. */
    private int warmReserved(@NonNull String templateName) {
        int count = 0;
        for (Reservation reservation : reservations.values()) {
            if (reservation.warm() && reservation.templateName().equals(templateName)) {
                count++;
            }
        }
        return count;
    }

    /**
     * One committed-but-unregistered agent: which template it is for, whether it is a warm spare, and when
     * this cloud stops believing in it.
     */
    private record Reservation(String templateName, boolean warm, long expiresAt) {}

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
            return Messages.XcpngCloud_DisplayName();
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
        @POST
        public FormValidation doCheckPoolUrl(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
                return FormValidation.error(Messages.XcpngCloud_poolUrl_malformed());
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return FormValidation.error(Messages.XcpngCloud_poolUrl_schemeMissing());
            }
            if (scheme.equalsIgnoreCase("http")) {
                // Plain http sends the XAPI credential (typically the pool's root password) and every
                // session token in cleartext. XAPI speaks TLS out of the box, and a self-signed pool
                // certificate is already handled by pinning its fingerprint, so no ordinary setup needs http.
                return FormValidation.error(Messages.XcpngCloud_poolUrl_http());
            }
            if (uri.getHost() == null) {
                return FormValidation.error(Messages.XcpngCloud_poolUrl_noHost());
            }
            if (uri.getUserInfo() != null) {
                // Credentials embedded in the URL (https://user:pass@host) would be persisted in
                // config.xml and could reach logs, against the store-the-ID-never-the-secret design.
                return FormValidation.error(Messages.XcpngCloud_poolUrl_userInfo());
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

        /**
         * Validate a pasted fingerprint's syntax. A wrong-but-well-formed fingerprint cannot be caught
         * here -- only the pool can say that, which is what Test connection is for -- but a truncated or
         * mistyped one can, and catching it on the form beats a handshake failure that looks like a
         * network problem.
         */
        @POST
        public FormValidation doCheckCertificateFingerprint(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.isBlank()) {
                // Empty is legitimate: a pool whose certificate chains to a CA the JVM already trusts
                // needs no pin. Test connection is what tells the operator which case they are in.
                return FormValidation.ok();
            }
            try {
                CertificateFingerprint.normalize(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(Messages.XcpngCloud_certificateFingerprint_malformed(e.getMessage()));
            }
        }

        @RequirePOST
        public FormValidation doTestConnection(
                @QueryParameter String poolUrl,
                @QueryParameter String credentialsId,
                @QueryParameter String certificateFingerprint) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (poolUrl == null || poolUrl.isBlank()) {
                return FormValidation.error(Messages.XcpngCloud_poolUrl_required());
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
            final String pin;
            try {
                pin = certificateFingerprint == null || certificateFingerprint.isBlank()
                        ? null
                        : CertificateFingerprint.normalize(certificateFingerprint);
            } catch (IllegalArgumentException e) {
                return FormValidation.error(Messages.XcpngCloud_certificateFingerprint_malformed(e.getMessage()));
            }
            StandardUsernamePasswordCredentials credentials = lookupCredentials(url, credentialsId);
            if (credentials == null) {
                return FormValidation.error(Messages.XcpngCloud_credentials_required());
            }
            try (XapiClient client = new XapiClient(
                    url, credentials.getUsername(), credentials.getPassword().getPlainText(), pin)) {
                client.ping();
                return connectedResult(pin);
            } catch (RuntimeException e) {
                // The button is admin-only and the message carries no secret, so it is returned to the
                // operator as the diagnostic they asked for; the stack trace is kept server-side. A
                // RuntimeException with no message (a bare NPE) would render as "Connection failed: null",
                // so fall back to a generic line and let the logged trace carry the detail.
                LOGGER.log(Level.WARNING, e, () -> "XCP-ng test connection to " + url + " failed");
                if (pin == null && isTlsFailure(e)) {
                    // The pool presented a certificate the JVM will not vouch for, which is the normal
                    // state of a stock XCP-ng host. This is the half of trust-on-first-use a human
                    // completes: show what was presented and let the operator confirm it is their pool.
                    return untrustedResult(url);
                }
                String detail = e.getMessage();
                return detail == null || detail.isBlank()
                        ? FormValidation.error(Messages.XcpngCloud_testConnection_failedNoDetail())
                        : FormValidation.error(Messages.XcpngCloud_testConnection_failed(detail));
            }
        }

        /**
         * True when {@code failure} was a TLS problem rather than an HTTP, credential or routing one.
         * Decided by walking the cause chain for {@link javax.net.ssl.SSLException}, because the client
         * wraps transport failures in {@code HypervisorException} and the distinction matters: only a TLS
         * failure should send the operator to the fingerprint, and a bad password over a perfectly good
         * connection must not.
         */
        private static boolean isTlsFailure(Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof javax.net.ssl.SSLException) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The pool's certificate is not trusted and nothing is pinned yet: read what it presents and offer
         * that fingerprint for the operator to confirm. Read with a trust manager that records the
         * certificate and then refuses it, so this diagnostic never itself completes a handshake with an
         * unverified peer -- nothing is sent, only looked at.
         */
        private static FormValidation untrustedResult(String url) {
            try {
                return FormValidation.warning(
                        Messages.XcpngCloud_testConnection_untrusted(CertificateFingerprint.fetch(url)));
            } catch (IOException probeFailure) {
                LOGGER.log(Level.WARNING, probeFailure, () -> "Could not read the certificate presented by " + url);
                return FormValidation.error(Messages.XcpngCloud_testConnection_probeFailed(probeFailure.getMessage()));
            }
        }

        /**
         * The result of a successful {@code Test connection}. Both outcomes are secure, so both are a
         * plain OK: either the pool's certificate chained to a CA the JVM trusts, or it matched the
         * fingerprint the operator pinned. The warning this method used to return -- connected, but over a
         * link with verification switched off -- no longer has a case that can produce it.
         */
        static FormValidation connectedResult(@CheckForNull String certificateFingerprint) {
            return certificateFingerprint == null
                    ? FormValidation.ok(Messages.XcpngCloud_testConnection_ok())
                    : FormValidation.ok(Messages.XcpngCloud_testConnection_okPinned());
        }
    }
}
