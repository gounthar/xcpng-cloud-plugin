package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
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
