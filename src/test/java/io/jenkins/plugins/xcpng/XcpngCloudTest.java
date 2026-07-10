package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class XcpngCloudTest {

    @Test
    void descriptorIsRegistered(JenkinsRule r) {
        assertNotNull(r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class));
    }

    /**
     * Saving the global config and reading it back must preserve every field.
     *
     * <p>This asserts round-tripping only. It cannot detect a secret written into the plugin's
     * config.xml, because a getter returns the same value whether or not the secret reached
     * disk. Guarding that needs a test which reads the persisted XML and asserts the plaintext
     * is absent from it. Worth writing when credentialsId lands.
     */
    @Test
    void configRoundTrip(JenkinsRule r) throws Exception {
        r.jenkins.clouds.add(new XcpngCloud("xcpng", "https://pool.example.test"));
        r.configRoundtrip();

        XcpngCloud reloaded = (XcpngCloud) r.jenkins.clouds.getByName("xcpng");
        assertNotNull(reloaded);
        assertEquals("https://pool.example.test", reloaded.getPoolUrl());
    }

    @Test
    void provisioningIsInertUntilM3(JenkinsRule r) {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test");
        assertEquals(0, cloud.provision(new hudson.slaves.Cloud.CloudState(null, 0), 4).size());
    }
}
