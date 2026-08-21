package io.jenkins.plugins.xcpng;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.model.CNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
        assertEquals(
                "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                cloud.getCertificateFingerprint());
        assertEquals(3, cloud.getMaxInstances());
        assertEquals(15, cloud.getIdleMinutes());
        assertEquals(1, cloud.getTemplates().size());

        XcpngTemplate template = cloud.getTemplates().get(0);
        assertEquals("jenkins-golden-debian", template.getTemplateName());
        assertEquals("xcpng-linux", template.getLabelString());
        assertEquals(4, template.getNumCpus());
        assertEquals(8192, template.getMemoryMb());
        assertEquals(1, template.getMinInstances());
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
        // Normalise line endings on both sides: SnakeYAML always emits LF, but a Windows or autocrlf
        // checkout reads the expected fixture back with CRLF (readResource takes it verbatim as bytes),
        // so a raw comparison would fail off Linux -- e.g. on a future ci.jenkins.io Windows leg.
        String exported = exportedClouds().replace("\r\n", "\n").trim();
        String expected = readResource("configuration-as-code-expected.yaml")
                .replace("\r\n", "\n")
                .trim();
        assertEquals(expected, exported, "the exported cloud config must match the expected YAML");
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

    /**
     * The single-key invariant is enforced on the way in, not merely flagged in the advisory form
     * validator, so a JCasC document carrying a multi-line {@code sshAuthorizedKey} (which would smuggle
     * extra keys or {@code authorized_keys} option prefixes to every clone) fails to load rather than
     * being applied verbatim.
     */
    @Test
    void multilineSshKeyInYamlFailsToLoad(JenkinsRule r) {
        assertThrows(
                ConfiguratorException.class,
                () -> ConfigurationAsCode.get()
                        .configure(getClass()
                                .getResource("configuration-as-code-multiline-sshkey.yaml")
                                .toExternalForm()));
    }

    /**
     * A document written before pinning existed must still apply. Before #143 this raised {@code
     * UnknownAttributesException}, which rejects the <em>whole</em> configuration document rather than
     * this one cloud, so a controller upgrading into #141 came up with none of its configuration and an
     * error about an attribute name. The retired key now binds, is refused, and the cloud fails closed
     * exactly as an upgraded {@code config.xml} does.
     */
    @Test
    void aLegacyTrustSelfSignedDocumentAppliesAndFailsClosed(JenkinsRule r) throws Exception {
        List<LogRecord> log = whileCapturingCloudLog(() -> ConfigurationAsCode.get()
                .configure(getClass()
                        .getResource("configuration-as-code-legacy-trust.yaml")
                        .toExternalForm()));

        XcpngCloud cloud = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-legacy");
        assertNotNull(cloud, "the document must apply rather than being rejected wholesale");
        assertNull(
                cloud.getCertificateFingerprint(),
                "the retired option must not be silently converted into a pin it never carried");
        assertTrue(
                log.stream()
                        .anyMatch(record -> record.getLevel() == Level.WARNING
                                && messageOf(record).contains("xcpng-legacy")
                                && messageOf(record).contains("Trust self-signed")),
                "the refusal must name the cloud and the removed option: "
                        + log.stream().map(LogRecord::getMessage).toList());
    }

    /**
     * The other half, and what makes the assertion above discriminate: a document carrying both the
     * retired key and a real fingerprint has already been migrated, so it keeps the pin and says nothing.
     */
    @Test
    void aHalfMigratedDocumentKeepsItsFingerprintSilently(JenkinsRule r) throws Exception {
        List<LogRecord> log = whileCapturingCloudLog(() -> ConfigurationAsCode.get()
                .configure(getClass()
                        .getResource("configuration-as-code-legacy-trust-migrated.yaml")
                        .toExternalForm()));

        XcpngCloud cloud = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-migrated");
        assertNotNull(cloud);
        assertEquals(
                "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                cloud.getCertificateFingerprint(),
                "the fingerprint must win over the retired key");
        assertTrue(
                log.stream().noneMatch(record -> messageOf(record).contains("Trust self-signed")),
                "a migrated cloud must not be warned about: "
                        + log.stream().map(LogRecord::getMessage).toList());
    }

    /**
     * A controller that imported a legacy document must not hand the retired key back out again.
     *
     * <p>{@code exportMatchesExpectedYaml} cannot catch this: it starts from a document that never carried
     * {@code trustSelfSigned}, so the value is already the default and would be omitted however the getter
     * behaved. Only a cloud that actually imported the old key can tell whether the export path resurrects
     * it, and an export that did would put a dead setting into configuration that outlives this migration.
     */
    @Test
    void exportOmitsTheRetiredKeyAfterALegacyImport(JenkinsRule r) throws Exception {
        ConfigurationAsCode.get()
                .configure(getClass()
                        .getResource("configuration-as-code-legacy-trust.yaml")
                        .toExternalForm());
        XcpngCloud cloud = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-legacy");
        assertNotNull(cloud, "the fixture must really have imported, or this proves nothing");

        String exported = exportedClouds();
        assertFalse(
                exported.contains("trustSelfSigned"),
                "an imported legacy cloud must not export the retired key: " + exported);
    }

    /** A record's message, never null: {@code LogRecord#getMessage} is nullable and the handler keeps all. */
    private static String messageOf(LogRecord record) {
        String message = record.getMessage();
        return message == null ? "" : message;
    }

    /** Collect everything {@link XcpngCloud} logs while {@code body} runs. */
    private static List<LogRecord> whileCapturingCloudLog(ThrowingRunnable body) throws Exception {
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger logger = Logger.getLogger(XcpngCloud.class.getName());
        logger.addHandler(handler);
        try {
            body.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    /** A body that may throw, so the capture helper can wrap ordinary test code. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
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
