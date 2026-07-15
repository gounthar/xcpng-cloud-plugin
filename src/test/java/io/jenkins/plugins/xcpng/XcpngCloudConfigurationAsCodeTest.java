package io.jenkins.plugins.xcpng;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.model.CNode;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The plugin carries no configuration-as-code glue of its own: every configurable field is a
 * {@code @DataBoundConstructor} parameter or a {@code @DataBoundSetter}, and both descriptors have a
 * {@code @Symbol}, so JCasC binds them reflectively. These tests are the proof that this really holds
 * end to end -- a controller can be stood up from {@code configuration-as-code.yaml} and its live
 * config exports back to the same YAML with no hand-written configurator.
 */
@WithJenkins
class XcpngCloudConfigurationAsCodeTest {

    private static final String YAML = "configuration-as-code.yaml";

    /** Applying the YAML must populate the cloud and its single template exactly as written. */
    @Test
    void importsFromYaml(JenkinsRule r) throws Exception {
        configureFromYaml();

        XcpngCloud cloud = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-lab");
        assertNotNull(cloud, "the xcpng cloud should be created from YAML");
        assertEquals("https://192.168.1.87", cloud.getPoolUrl());
        assertEquals("xcpng-root", cloud.getCredentialsId());
        assertTrue(cloud.isTrustSelfSigned());
        assertEquals(3, cloud.getMaxInstances());
        assertEquals(1, cloud.getTemplates().size());

        XcpngTemplate template = cloud.getTemplates().get(0);
        assertEquals("jenkins-golden-debian", template.getTemplateName());
        assertEquals("xcpng-linux", template.getLabelString());
        assertEquals(2, template.getNumExecutors());
        assertEquals(4, template.getNumCpus());
        assertEquals(8192, template.getMemoryMb());
        assertEquals("ssh-ed25519 AAAAExampleKeyForRoundTripTest lab", template.getSshAuthorizedKey());
    }

    /**
     * Export the live {@code clouds} config back to YAML and compare it byte for byte to the expected
     * document. A field that failed to export, or that exported under an unexpected key, would diverge
     * from the fixture and fail here -- this is the guard that the annotation-only binding stays complete.
     */
    @Test
    void exportMatchesExpectedYaml(JenkinsRule r) throws Exception {
        configureFromYaml();
        String exported = exportedClouds();
        String expected = readResource("configuration-as-code-expected.yaml").trim();
        assertEquals(expected, exported.trim(), "the exported cloud config must match the expected YAML");
    }

    /**
     * The credential is exported by ID only; the secret it points at must never appear in the YAML.
     * Seed a real credential under the ID the fixture references with a unique sentinel password, so the
     * assertion proves an actual secret is excluded rather than merely that the literal word "password"
     * is absent.
     */
    @Test
    void exportKeepsCredentialAsIdOnly(JenkinsRule r) throws Exception {
        String secret = "s3ntinel-secret-must-not-export";
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL, "xcpng-root", "XCP-ng lab", "root", secret));
        SystemCredentialsProvider.getInstance().save();

        configureFromYaml();
        String exported = exportedClouds();
        assertTrue(exported.contains("xcpng-root"), "the credential ID should be exported");
        assertFalse(exported.contains(secret), "the credential's secret must never appear in the export");
    }

    private void configureFromYaml() throws Exception {
        ConfigurationAsCode.get().configure(getClass().getResource(YAML).toExternalForm());
    }

    /** The {@code clouds} subtree of the live Jenkins config, serialised to YAML. */
    private static String exportedClouds() throws Exception {
        ConfigurationContext context = new ConfigurationContext(ConfiguratorRegistry.get());
        CNode clouds = getJenkinsRoot(context).get("clouds");
        return toYamlString(clouds);
    }

    private String readResource(String name) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
