package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * One kind of agent this cloud can provision: which golden-image template to clone, the labels the
 * resulting agent serves, how many executors it runs, and the size of the clone.
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

    /** Fallbacks for a clone whose size was not set, matching the lab golden image (2 vCPU / 2 GiB). */
    private static final int DEFAULT_NUM_CPUS = 2;

    private static final int DEFAULT_MEMORY_MB = 2048;

    private final String templateName;
    private final String labelString;
    private int numExecutors;
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
    public XcpngTemplate(String templateName, String labelString, int numExecutors, int numCpus, int memoryMb) {
        this.templateName = templateName;
        this.labelString = labelString;
        this.numExecutors = numExecutors <= 0 ? 1 : numExecutors;
        this.numCpus = numCpus <= 0 ? DEFAULT_NUM_CPUS : numCpus;
        this.memoryMb = memoryMb <= 0 ? DEFAULT_MEMORY_MB : memoryMb;
    }

    /**
     * XStream loads a persisted template without the constructor, so a config.xml predating the sizing
     * fields (or carrying a zero left by a hand-edit) reloads with {@code numCpus}/{@code memoryMb} at
     * 0. Re-apply the same clamps the constructor does, so provisioning never builds an invalid spec.
     */
    protected Object readResolve() {
        if (numExecutors <= 0) {
            numExecutors = 1;
        }
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
            sshAuthorizedKey = trimmed.isEmpty() ? null : trimmed;
        }
        // A config predating this field deserializes it to 0, which is already the "off" default; this
        // only floors a hand-edited negative, mirroring the setter's clamp.
        if (minInstances < 0) {
            minInstances = 0;
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

    public int getNumExecutors() {
        return numExecutors;
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
        this.sshAuthorizedKey = trimmed == null || trimmed.isEmpty() ? null : trimmed;
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
            return "XCP-ng agent template";
        }

        public FormValidation doCheckTemplateName(@QueryParameter String value) {
            return value == null || value.isBlank()
                    ? FormValidation.error("The golden-image template name is required.")
                    : FormValidation.ok();
        }

        public FormValidation doCheckNumCpus(@QueryParameter String value) {
            return checkPositiveInt(value, "The number of vCPUs");
        }

        public FormValidation doCheckMemoryMb(@QueryParameter String value) {
            return checkPositiveInt(value, "The memory (MiB)");
        }

        /**
         * The warm-pool size accepts 0 (the "off" default), so it takes the non-negative check rather
         * than the positive one the sizing fields use.
         */
        public FormValidation doCheckMinInstances(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                return Integer.parseInt(value.trim()) >= 0
                        ? FormValidation.ok()
                        : FormValidation.error("Min instances cannot be negative.");
            } catch (NumberFormatException e) {
                return FormValidation.error("Min instances must be a whole number.");
            }
        }

        /**
         * Accepted OpenSSH public-key type prefixes; anything else is almost certainly not a pubkey.
         * ssh-dss (DSA) is deliberately absent: current OpenSSH disables it, so such a key would validate
         * here yet never authenticate on the agent.
         */
        private static final String[] PUBLIC_KEY_PREFIXES = {
            "ssh-ed25519 ", "ssh-rsa ", "ecdsa-sha2-", "sk-ssh-", "sk-ecdsa-"
        };

        /**
         * Optional field, so blank is fine. Otherwise require something that looks like an OpenSSH
         * public key, and reject a pasted private key outright: the private half must never reach the
         * plugin, let alone a guest.
         */
        public FormValidation doCheckSshAuthorizedKey(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            String key = value.trim();
            if (key.contains("\n") || key.contains("\r")) {
                // Trimming strips only the ends, so an embedded newline would otherwise smuggle a whole
                // block of keys past a first-line prefix check, and the seed writes the value verbatim
                // into the agent's authorized_keys. One key, one line.
                return FormValidation.error(
                        "Enter a single public key on one line; line breaks and multiple keys are not accepted.");
            }
            if (key.contains("PRIVATE KEY")) {
                return FormValidation.error(
                        "That looks like a private key. Paste the public key (e.g. the contents of a .pub file);"
                                + " the private key must stay with you and never reaches the agent.");
            }
            for (String prefix : PUBLIC_KEY_PREFIXES) {
                if (key.startsWith(prefix)) {
                    return FormValidation.ok();
                }
            }
            return FormValidation.error(
                    "Enter a single OpenSSH public key, starting with a type such as ssh-ed25519 or ssh-rsa.");
        }

        private static FormValidation checkPositiveInt(String value, String what) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                return Integer.parseInt(value.trim()) > 0
                        ? FormValidation.ok()
                        : FormValidation.error(what + " must be a positive whole number.");
            } catch (NumberFormatException e) {
                return FormValidation.error(what + " must be a whole number.");
            }
        }
    }
}
