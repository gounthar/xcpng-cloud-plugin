package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.FormValidation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class XcpngCloudTest {

    @Test
    void descriptorIsRegistered(JenkinsRule r) {
        assertNotNull(r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class));
    }

    /** Saving the global config and reading it back must preserve every field. */
    @Test
    void configRoundTrip(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(new XcpngCloud(
                "xcpng",
                "https://pool.example.test",
                "xcpng-root",
                true,
                3,
                List.of(new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2))));
        r.configRoundtrip();

        XcpngCloud reloaded = (XcpngCloud) r.jenkins.clouds.getByName("xcpng");
        assertNotNull(reloaded);
        assertEquals("https://pool.example.test", reloaded.getPoolUrl());
        assertEquals("xcpng-root", reloaded.getCredentialsId());
        assertTrue(reloaded.isTrustSelfSigned());
        assertEquals(3, reloaded.getMaxInstances());
        assertEquals(1, reloaded.getTemplates().size());

        XcpngTemplate template = reloaded.getTemplates().get(0);
        assertEquals("jenkins-golden-debian", template.getTemplateName());
        assertEquals("xcpng-linux", template.getLabelString());
        assertEquals(2, template.getNumExecutors());
    }

    /**
     * The design stores a credential ID, never the secret. Referencing a real credential by ID and
     * persisting the cloud must leave the plaintext password out of Jenkins' global config.xml.
     */
    @Test
    void credentialSecretIsNotPersisted(JenkinsRule r) throws Exception {
        String secret = "sup3r-s3cret-pw";
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "xcpng-root", "XCP-ng lab", "root", secret));
        SystemCredentialsProvider.getInstance().save();

        r.jenkins.clouds.add(new XcpngCloud(
                "xcpng",
                "https://pool.example.test",
                "xcpng-root",
                true,
                2,
                List.of(new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 1))));
        r.jenkins.save();

        Path configXml = r.jenkins.getRootDir().toPath().resolve("config.xml");
        String xml = Files.readString(configXml);
        assertTrue(xml.contains("xcpng-root"), "the credential ID should be stored");
        assertFalse(xml.contains(secret), "the plaintext password must never reach the cloud config");
    }

    /**
     * The pool URL check accepts an http/https address with a host and rejects everything else, so a
     * schemeless string that {@code new URI(...)} would parse without complaint is still flagged.
     */
    @Test
    void poolUrlValidation(JenkinsRule r) {
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolUrl("https://192.168.1.87").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolUrl("http://pool.example.test:443").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolUrl("").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("192.168.1.87").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("ftp://pool").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("https://").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckPoolUrl("http://[bad").kind);
    }

    /**
     * The connection test applies the same scheme/host check as the field validator, so a malformed
     * URL is rejected with that message up front rather than failing deeper in the client. A schemeless
     * value must not fall through to the credentials error.
     */
    @Test
    void testConnectionRejectsMalformedUrlBeforeConnecting(JenkinsRule r) {
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);
        FormValidation v = d.doTestConnection("192.168.1.87", "", false);
        assertEquals(FormValidation.Kind.ERROR, v.kind);
        assertTrue(v.getMessage().contains("http"), v.getMessage());
    }

    @Test
    void provisioningIsInertUntilWired(JenkinsRule r) {
        XcpngCloud cloud = new XcpngCloud(
                "xcpng",
                "https://pool.example.test",
                "xcpng-root",
                false,
                2,
                List.of(new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 1)));
        assertEquals(0, cloud.provision(new hudson.slaves.Cloud.CloudState(null, 0), 4).size());
    }
}
