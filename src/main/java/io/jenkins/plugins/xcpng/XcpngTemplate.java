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
 * resulting agent serves, and how many executors it runs.
 *
 * <p>v0 supports a single template per cloud (the plan cuts multi-template). {@link XcpngCloud} holds
 * a list only because that is the standard shape of a cloud configuration form; provisioning uses the
 * first template whose labels match the queued work.
 */
public class XcpngTemplate extends AbstractDescribableImpl<XcpngTemplate> {

    private final String templateName;
    private final String labelString;
    private final int numExecutors;

    @DataBoundConstructor
    public XcpngTemplate(String templateName, String labelString, int numExecutors) {
        this.templateName = templateName;
        this.labelString = labelString;
        this.numExecutors = numExecutors <= 0 ? 1 : numExecutors;
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
    }
}
