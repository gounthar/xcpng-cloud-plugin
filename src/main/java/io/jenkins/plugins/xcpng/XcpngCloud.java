package io.jenkins.plugins.xcpng;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.xcpng.client.XapiClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
 * <p>M3 slice 3: the configuration model and its {@code doTestConnection}. Wiring
 * {@link #provision} to the build queue is the next slice, so provisioning stays inert here.
 */
public class XcpngCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(XcpngCloud.class.getName());

    private final String poolUrl;
    private final String credentialsId;
    private final boolean trustSelfSigned;
    private final int maxInstances;
    private final List<XcpngTemplate> templates;

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
        return false;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        return List.of();
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
