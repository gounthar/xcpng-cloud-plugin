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
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
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
     * Idle timeout before an agent that connected but never received work is reclaimed. A constant in
     * v0: the dominant path is single-use (destroyed after one build), and this is only the safety net.
     * A per-cloud form field is a later refinement.
     */
    private static final int RETENTION_IDLE_MINUTES = 10;

    /** Names provisioned VMs and agents uniquely within a controller run. */
    private static final AtomicInteger PROVISION_COUNTER = new AtomicInteger();

    private final String poolUrl;
    private final String credentialsId;
    private final boolean trustSelfSigned;
    // Not final: readResolve re-applies the constructor's guards when XStream loads an older config
    // that predates these fields (the constructor does not run on deserialization).
    private int maxInstances;
    private List<XcpngTemplate> templates;

    /**
     * How a live client is opened. Null in production, where {@link #openClient()} builds an
     * {@link XapiClient} from the configured credentials; a test injects an in-memory fake here.
     * Transient: it is behaviour, not configuration, and must never be persisted to {@code config.xml}.
     */
    private transient HypervisorClientFactory clientFactory;

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

    @NonNull
    public List<XcpngTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    @Override
    public boolean canProvision(CloudState state) {
        return templateFor(state.getLabel()) != null && availableCapacity() > 0;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
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
            final String displayName = "xcpng-" + template.getTemplateName() + "-" + PROVISION_COUNTER.incrementAndGet();
            Future<Node> future = Computer.threadPoolForRemoting.submit(() -> provisionNode(template, displayName));
            planned.add(new NodeProvisioner.PlannedNode(displayName, future, template.getNumExecutors()));
            remaining -= template.getNumExecutors();
        }
        return planned;
    }

    /**
     * Clone the template, start it, and wrap the running VM in a single-use inbound agent. Called on a
     * background thread by {@link #provision}; the returned {@link Node} is added to Jenkins by the
     * node provisioner once this future completes. Package-visible so a test can drive it against a
     * fake client and assert the clone/start call sequence.
     */
    Node provisionNode(@NonNull XcpngTemplate template, @NonNull String displayName) throws Exception {
        final VmRef clone;
        try (HypervisorClient client = openClient()) {
            VmRef templateRef = client.resolveTemplate(template.getTemplateName());
            ProvisionSpec spec = new ProvisionSpec(
                    displayName, template.getNumCpus(), template.getMemoryBytes(), null, null, null);
            clone = client.cloneFromTemplate(templateRef, spec);
            client.start(clone);
        }
        LOGGER.log(Level.INFO, () -> "Provisioned XCP-ng VM " + clone.value() + " as agent " + displayName);
        return new XcpngAgent(displayName, name, clone.value(), template, RETENTION_IDLE_MINUTES);
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

    /** Instance-cap headroom: the configured maximum minus the agents this cloud already runs. */
    private int availableCapacity() {
        int active = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof XcpngAgent) {
                active++;
            }
        }
        return Math.max(0, maxInstances - active);
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
