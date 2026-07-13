package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
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
