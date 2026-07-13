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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
        this.poolUrl = poolUrl;
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
            StandardUsernamePasswordCredentials credentials = lookupCredentials(poolUrl, credentialsId);
            if (credentials == null) {
                return FormValidation.error("Select the XAPI credentials.");
            }
            try (XapiClient client = new XapiClient(
                    poolUrl,
                    credentials.getUsername(),
                    credentials.getPassword().getPlainText(),
                    trustSelfSigned)) {
                client.ping();
                return FormValidation.ok("Connected to the pool.");
            } catch (RuntimeException e) {
                return FormValidation.error("Connection failed: " + e.getMessage());
            }
        }
    }
}
