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
     * Saving the global config and reading it back must preserve every field. This is the
     * guard that later stops a secret from being persisted into the plugin's own config.xml:
     * once credentialsId lands here, this test asserts an ID round-trips and nothing else does.
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
