package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * One kind of agent this cloud can provision: which golden-image template to clone, the labels the
 * resulting agent serves, and the size of the clone.
 *
 * <p>Executors are not among them. Every agent runs exactly one (see {@link XcpngAgent#EXECUTORS_PER_AGENT}),
 * because single-use and a second executor cannot both hold: the reap fires on the first build's completion
 * and would destroy the VM under any build still running beside it. Scale with more clones instead.
 *
 * <p>v0 supports a single template per cloud (the plan cuts multi-template). {@link XcpngCloud} holds
 * a list only because that is the standard shape of a cloud configuration form; provisioning uses the
 * first template whose labels match the queued work.
 *
 * <p>Sizing is per-template because operators size their fleets, not their golden images: an operator
 * clones one golden image into 2-vCPU and 8-vCPU agents for different labels. {@code VM.clone} copies
 * the source's vCPU and memory, so provisioning overrides them from these fields. Memory is entered in
 * MiB and converted to bytes at the {@code HypervisorClient} seam; disk is left to inherit the golden
 * image (a genericcloud root filesystem auto-grows on first boot).
 */
public class XcpngTemplate extends AbstractDescribableImpl<XcpngTemplate> {

    private static final Logger LOGGER = Logger.getLogger(XcpngTemplate.class.getName());

    /** Fallbacks for a clone whose size was not set, matching the lab golden image (2 vCPU / 2 GiB). */
    private static final int DEFAULT_NUM_CPUS = 2;

    private static final int DEFAULT_MEMORY_MB = 2048;

    /**
     * Accepted OpenSSH public-key type prefixes; anything else is almost certainly not a pubkey.
     * ssh-dss (DSA) is deliberately absent: current OpenSSH disables it, so such a key would validate
     * here yet never authenticate on the agent.
     */
    private static final String[] PUBLIC_KEY_PREFIXES = {
        "ssh-ed25519 ", "ssh-rsa ", "ecdsa-sha2-", "sk-ssh-", "sk-ecdsa-"
    };

    /**
     * The single-key invariant, enforced everywhere the value enters the plugin rather than only in the
     * advisory form validator. The seed writes the value verbatim into the clone's {@code
     * authorized_keys}, so it must be one public key on one line: no line breaks (which would smuggle
     * extra keys or {@code authorized_keys} option prefixes such as {@code command=...} past a first-line
     * check), no private-key material, and a recognised OpenSSH type prefix. Returns a human-readable
     * reason the value is unacceptable, or {@code null} when it is a well-formed single public key.
     * Assumes the value is already trimmed and non-empty.
     */
    @CheckForNull
    static String sshAuthorizedKeyProblem(@NonNull String trimmed) {
        if (trimmed.contains("\n") || trimmed.contains("\r")) {
            return "Enter a single public key on one line; line breaks and multiple keys are not accepted.";
        }
        if (trimmed.contains("PRIVATE KEY")) {
            return "That looks like a private key. Paste the public key (e.g. the contents of a .pub file);"
                    + " the private key must stay with you and never reaches the agent.";
        }
        for (String prefix : PUBLIC_KEY_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return null;
            }
        }
        return "Enter a single OpenSSH public key, starting with a type such as ssh-ed25519 or ssh-rsa.";
    }

    private final String templateName;
    private final String labelString;
    private int numCpus;
    private int memoryMb;
    // Optional: an OpenSSH *public* key. When set, each clone trusts it for the debian user (delivered
    // per-clone over the same xenstore channel as the JNLP secret; the private half never touches a
    // guest). Null when unset, which is the norm: the inbound launcher needs no SSH at all.
    @CheckForNull
    private String sshAuthorizedKey;

    /**
     * Warm-pool target: how many pre-booted, idle agents of this template to keep hot so a queued
     * build lands on a ready executor instead of waiting for a cold clone. Optional; 0 (the default)
     * turns the warm pool off, which is why it clamps to 0 rather than a positive floor like the
     * cloud's {@code idleMinutes}. Each warm agent is still single-use: it runs one build and is
     * destroyed, and the pool maintainer boots a replacement.
     */
    private int minInstances;

    @DataBoundConstructor
    public XcpngTemplate(String templateName, String labelString, int numCpus, int memoryMb) {
        this.templateName = templateName;
        this.labelString = labelString;
        this.numCpus = numCpus <= 0 ? DEFAULT_NUM_CPUS : numCpus;
        this.memoryMb = memoryMb <= 0 ? DEFAULT_MEMORY_MB : memoryMb;
    }

    /**
     * XStream loads a persisted template without the constructor, so a config.xml predating the sizing
     * fields (or carrying a zero left by a hand-edit) reloads with {@code numCpus}/{@code memoryMb} at
     * 0. Re-apply the same clamps the constructor does, so provisioning never builds an invalid spec.
     *
     * <p>A config.xml written before executors were pinned still carries a {@code <numExecutors>} element.
     * Nothing reads it: Jenkins' robust reflection converter drops an element with no matching field, and
     * the agent takes {@link XcpngAgent#EXECUTORS_PER_AGENT} regardless. A persisted 2 therefore stops
     * meaning "two builds share this VM" on reload, which is the point of the change, not a casualty of it.
     */
    protected Object readResolve() {
        if (numCpus <= 0) {
            numCpus = DEFAULT_NUM_CPUS;
        }
        if (memoryMb <= 0) {
            memoryMb = DEFAULT_MEMORY_MB;
        }
        // XStream populates fields directly, bypassing the DataBoundSetter's normalization, so a
        // hand-edited config.xml with a blank or whitespace key would survive as non-null and get seeded
        // as an empty authorized_keys line. Collapse it to null here, matching the setter.
        if (sshAuthorizedKey != null) {
            String trimmed = sshAuthorizedKey.trim();
            if (trimmed.isEmpty()) {
                sshAuthorizedKey = null;
            } else {
                // Apply the same single-key invariant as the setter. Here the safe move is to drop the
                // bad value and log rather than throw: one hand-edited template must not stop the whole
                // controller loading, and a dropped key just leaves that agent inbound-only.
                String problem = sshAuthorizedKeyProblem(trimmed);
                if (problem != null) {
                    LOGGER.log(
                            Level.WARNING,
                            () -> "Dropping the SSH authorized key on template " + templateName + ": " + problem);
                    sshAuthorizedKey = null;
                } else {
                    sshAuthorizedKey = trimmed;
                }
            }
        }
        // A config predating this field deserializes it to 0, which is already the "off" default; this
        // only floors a hand-edited negative, mirroring the setter's clamp.
        if (minInstances < 0) {
            minInstances = 0;
        }
        // Labels used to be optional in effect: a template with none served builds that asked for nothing.
        // Agents are EXCLUSIVE now and the cloud declines a null label, so such a template provisions
        // nothing at all. An upgraded controller would simply stop cloning, with nothing to read anywhere,
        // so say it at load. Warn rather than reject, for the same reason the SSH key above is dropped
        // rather than thrown on: one template must not stop the controller loading. The field is final,
        // so there is nothing to normalise here — doCheckLabelString is where it gets fixed.
        if (labelString == null || labelString.isBlank()) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "Template " + templateName + " has no labels, so nothing will ever be provisioned"
                            + " from it: these agents only run builds whose label expression matches."
                            + " Set a label on the template in the cloud's configuration.");
        }
        return this;
    }

    /** Name of the golden-image template on the pool to clone, e.g. {@code jenkins-golden-debian}. */
    public String getTemplateName() {
        return templateName;
    }

    /** Space-separated labels the provisioned agent serves. */
    public String getLabelString() {
        return labelString;
    }

    /** Virtual CPUs the clone is sized to, overriding whatever the golden image carried. */
    public int getNumCpus() {
        return numCpus;
    }

    /** Clone memory in MiB, as a human enters it. See {@link #getMemoryBytes()} for the seam value. */
    public int getMemoryMb() {
        return memoryMb;
    }

    /** Clone memory in bytes, as {@code ProvisionSpec} and the XAPI backend expect it. */
    public long getMemoryBytes() {
        return memoryMb * 1024L * 1024L;
    }

    /**
     * The operator-supplied OpenSSH public key each clone trusts for the debian user, or null when
     * none is set. Optional, and opt-in: an inbound-only agent never needs it. Stored trimmed.
     */
    @CheckForNull
    public String getSshAuthorizedKey() {
        return sshAuthorizedKey;
    }

    @DataBoundSetter
    public void setSshAuthorizedKey(@CheckForNull String sshAuthorizedKey) {
        // Normalise a blank textarea to null so getSshAuthorizedKey() is either a real key or nothing,
        // and the seed logic can gate on non-null rather than re-checking for blank.
        String trimmed = sshAuthorizedKey == null ? null : sshAuthorizedKey.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            this.sshAuthorizedKey = null;
            return;
        }
        // doCheckSshAuthorizedKey is advisory: the form, a JCasC document, or a direct config submit
        // save regardless of a red validation message. Enforce the single-key invariant here too, so a
        // multi-line value smuggling extra keys or authorized_keys option prefixes fails loudly at load
        // instead of being seeded verbatim to every clone.
        String problem = sshAuthorizedKeyProblem(trimmed);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        this.sshAuthorizedKey = trimmed;
    }

    /** Warm-pool target: pre-booted idle agents of this template to keep hot. 0 disables the warm pool. */
    public int getMinInstances() {
        return minInstances;
    }

    /**
     * Optional warm-pool size. Clamped so a negative value cannot mean "negative agents": 0 is the
     * valid "off" value, so this floors at 0 rather than at a positive default.
     */
    @DataBoundSetter
    public void setMinInstances(int minInstances) {
        this.minInstances = Math.max(0, minInstances);
    }

    @Extension
    @Symbol("xcpngTemplate")
    public static class DescriptorImpl extends Descriptor<XcpngTemplate> {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.XcpngTemplate_DisplayName();
        }

        /**
         * How the template-name check opens a pool session. Production builds an {@code XapiClient} from
         * the connection fields the form is carrying; a test injects a fake through {@link #setPoolProbe}.
         * An instance field rather than a static one because the descriptor is a singleton per Jenkins and
         * {@code JenkinsRule} builds a fresh Jenkins per test, so nothing leaks between tests.
         */
        @FunctionalInterface
        interface PoolProbe {
            @NonNull
            HypervisorClient open(
                    @CheckForNull String poolUrl,
                    @CheckForNull String credentialsId,
                    @CheckForNull String certificateFingerprint);
        }

        private PoolProbe poolProbe = (poolUrl, credentialsId, certificateFingerprint) ->
                XcpngCloud.openClient(poolUrl, credentialsId, certificateFingerprint, "the template name check");

        /** Test seam: resolve template names against an in-memory fake instead of a real pool. */
        void setPoolProbe(@NonNull PoolProbe poolProbe) {
            this.poolProbe = poolProbe;
        }

        /**
         * The name is required, and — when the form is carrying enough of the enclosing cloud's connection
         * detail to ask — it is also resolved against the pool, so a typo is named here rather than in the
         * build queue. Without this the only symptom is #157: a template naming an image the pool does not
         * have fails a provision on every provisioning round, once per round, for as long as a build waits,
         * with no message clearer than the first one.
         *
         * <p>The connection fields come from the enclosing cloud through {@link RelativePath}, not from a
         * saved {@code XcpngCloud}, because the operator may be typing them right now and the value being
         * checked has to be the one they can see. Core infers {@code checkDependsOn} from this signature,
         * so editing any of the three re-runs the check as well.
         *
         * <p>This does not remove the runtime case — a golden image can be deleted after the cloud is
         * saved, and #157's retry-forever half is still open — it moves the common case to where it is
         * cheap. The validator fires on page load, on this field's own change, and on a change to any of
         * the three it depends on, so the cost is roughly a Test connection rather than one per keystroke.
         */
        @POST
        public FormValidation doCheckTemplateName(
                @QueryParameter String value,
                @RelativePath("..") @QueryParameter String poolUrl,
                @RelativePath("..") @QueryParameter String credentialsId,
                @RelativePath("..") @QueryParameter String certificateFingerprint) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.isBlank()) {
                return FormValidation.error(Messages.XcpngTemplate_templateName_required());
            }
            return resolveAgainstPool(value.trim(), poolUrl, credentialsId, certificateFingerprint);
        }

        /**
         * Ask the pool whether {@code name} resolves, and say so only when the pool actually answered.
         *
         * <p>Every way of failing to reach the pool returns {@code ok()}: an unreachable host, a wrong
         * password and a missing credential are all Test connection's story to tell, and reporting them
         * under this field would blame the template name for a problem it did not cause — and would put a
         * red error on a half-filled form, which is the state every form starts in. The discriminator is
         * {@code ping()}: once it has succeeded the pool is reachable and the credential works, so anything
         * {@code resolveTemplate} says after that is about the name.
         */
        private FormValidation resolveAgainstPool(
                @NonNull String name,
                @CheckForNull String poolUrl,
                @CheckForNull String credentialsId,
                @CheckForNull String certificateFingerprint) {
            if (poolUrl == null || poolUrl.isBlank()) {
                return FormValidation.ok();
            }
            String url = poolUrl.trim();
            if (XcpngCloud.DescriptorImpl.validatePoolUrlFormat(url).kind != FormValidation.Kind.OK) {
                // doCheckPoolUrl is already saying what is wrong with it; do not say it twice, and do
                // not hand a malformed URL to the client.
                return FormValidation.ok();
            }
            HypervisorClient client;
            try {
                client = poolProbe.open(url, credentialsId, certificateFingerprint);
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "Could not open a session to " + url + " to check a template name");
                return FormValidation.ok();
            }
            try {
                try {
                    client.ping();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.FINE, e, () -> "Could not reach " + url + " to check a template name");
                    return FormValidation.ok();
                }
                try {
                    client.resolveTemplate(name);
                    return FormValidation.ok();
                } catch (RuntimeException e) {
                    // A successful ping does not stay true. The pool can drop between the two calls, and the
                    // exception cannot say which happened: an absent name and a dead connection are both a
                    // HypervisorException with a null error code (XapiClient.raw builds the transport one that
                    // way, resolveTemplate builds the not-found one that way), so there is nothing on it to
                    // branch on. Ask the pool again instead. If it still answers, it answered about the name.
                    try {
                        client.ping();
                    } catch (RuntimeException poolWentAway) {
                        LOGGER.log(Level.FINE, e, () -> "Lost " + url + " while checking a template name");
                        return FormValidation.ok();
                    }
                    // The client's own message names the case (absent, not a template, or ambiguous) and is
                    // more specific than anything reconstructed here would be.
                    String detail = e.getMessage();
                    return detail == null || detail.isBlank()
                            ? FormValidation.error(Messages.XcpngTemplate_templateName_unresolvedNoDetail(name))
                            : FormValidation.error(Messages.XcpngTemplate_templateName_unresolved(detail));
                }
            } finally {
                // Closed by hand rather than with try-with-resources: a close() that threw would replace
                // the error just computed and the check would silently pass.
                try {
                    client.close();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.FINE, e, () -> "Releasing the session used to check a template name failed");
                }
            }
        }

        /**
         * Labels are required, because they are the only way a build can reach these agents. Provisioned
         * nodes are {@link XcpngAgent#USAGE_MODE} — {@code EXCLUSIVE} — and {@link XcpngCloud} declines to
         * provision for a build with no label expression, so a template with no labels is not a
         * general-purpose template, it is one nothing can ever schedule onto. Before those two changed it
         * silently meant "serve unlabeled builds"; this says so at the form rather than leaving a config
         * that looks configured and provisions nothing.
         */
        @POST
        public FormValidation doCheckLabelString(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return value == null || value.isBlank()
                    ? FormValidation.error(Messages.XcpngTemplate_labelString_required())
                    : FormValidation.ok();
        }

        @POST
        public FormValidation doCheckNumCpus(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return checkPositiveInt(value, Messages.XcpngTemplate_numCpus_label());
        }

        @POST
        public FormValidation doCheckMemoryMb(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return checkPositiveInt(value, Messages.XcpngTemplate_memoryMb_label());
        }

        /**
         * The warm-pool size accepts 0 (the "off" default), so it takes the non-negative check rather
         * than the positive one the sizing fields use. When the enclosing cloud is in the form path, also
         * warn if the target exceeds the cloud's instance cap: warm agents count against {@code
         * maxInstances}, so a larger target can never be filled.
         */
        @POST
        public FormValidation doCheckMinInstances(@AncestorInPath XcpngCloud cloud, @QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            int count;
            try {
                count = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return FormValidation.error(Messages.XcpngTemplate_minInstances_notWhole());
            }
            if (count < 0) {
                return FormValidation.error(Messages.XcpngTemplate_minInstances_negative());
            }
            if (cloud != null && count > cloud.getMaxInstances()) {
                return FormValidation.warning(Messages.XcpngTemplate_minInstances_exceedsMax(
                        String.valueOf(count), String.valueOf(cloud.getMaxInstances())));
            }
            return FormValidation.ok();
        }

        /**
         * The friendly form-time layer over the same rule the setter and {@code readResolve} enforce
         * ({@link XcpngTemplate#sshAuthorizedKeyProblem}), so the UI message and the load-time invariant
         * cannot drift apart. Optional field, so blank is fine.
         */
        @POST
        public FormValidation doCheckSshAuthorizedKey(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            String problem = sshAuthorizedKeyProblem(value.trim());
            return problem == null ? FormValidation.ok() : FormValidation.error(problem);
        }

        private static FormValidation checkPositiveInt(String value, String what) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                return Integer.parseInt(value.trim()) > 0
                        ? FormValidation.ok()
                        : FormValidation.error(Messages.XcpngTemplate_field_notPositive(what));
            } catch (NumberFormatException e) {
                return FormValidation.error(Messages.XcpngTemplate_field_notWhole(what));
            }
        }
    }
}
