package io.jenkins.plugins.xcpng;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.model.CNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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

    /**
     * A configuration-as-code reload must not forget the VMs a teardown failed to destroy (#149).
     *
     * <p>JCasC rebuilds {@code jenkins.clouds} from the document through the
     * {@code @DataBoundConstructor}, so every field that is not configuration comes back at its initial
     * value. The leaked-VM set used to be such a field, and a reload therefore dropped the last reference to
     * a VM nothing else was going to retry. It now lives in {@link XcpngLeakedVmStore}, keyed by cloud name,
     * which is what a rebuilt cloud carries across.
     */
    @Test
    void aJcascReloadKeepsTheLeakedVmSet(JenkinsRule r) throws Exception {
        configureFromYaml();
        XcpngCloud before = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-lab");
        assertNotNull(before, "the fixture must really have imported, or this proves nothing");
        before.recordLeakedVm("OpaqueRef:leaked-vm-1");

        configureFromYaml();

        XcpngCloud after = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-lab");
        assertNotNull(after, "the reload must leave a cloud behind");
        assertNotEquals(
                System.identityHashCode(before),
                System.identityHashCode(after),
                "a reload is expected to rebuild the cloud; if it stopped doing so this test proves nothing");
        assertEquals(
                Set.of("OpaqueRef:leaked-vm-1"),
                after.leakedVmRefs(),
                "a JCasC reload must not forget a VM the plugin failed to destroy");
    }

    /**
     * A configuration-as-code reload must not free a slot the cloud has already committed (#160).
     *
     * <p>The sibling of {@link #aJcascReloadKeepsTheLeakedVmSet}, and the same shape: runtime state on an
     * object that a reload rebuilds. A reservation covers the round in which a planned node is in neither
     * {@code Jenkins.getNodes()} nor any in-flight count, and both capacity formulas subtract it, so losing
     * one to a reload lets the next pass plan past {@code maxInstances}.
     *
     * <p>Asserted by node name rather than by {@code inFlightCount()} on purpose. The fixture's template
     * carries {@code minInstances: 1}, so a warm-pool tick landing mid-test would take a reservation of its
     * own and make a count assertion pass whether or not the committed slot survived.
     */
    @Test
    void aJcascReloadKeepsReservations(JenkinsRule r) throws Exception {
        configureFromYaml();
        XcpngCloud before = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-lab");
        assertNotNull(before, "the fixture must really have imported, or this proves nothing");

        // provision() opens no client: it builds the node object and reserves the slot, and XcpngLauncher
        // does the cloning later, when core connects the computer. So a reservation can be taken here
        // without a pool to talk to. The node is never registered, so nothing releases it.
        Collection<NodeProvisioner.PlannedNode> planned =
                before.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 1);
        assertEquals(1, planned.size(), "the cloud must plan an agent for its own label");
        String nodeName = planned.iterator().next().displayName;
        assertTrue(
                XcpngReservationStore.get().holds("xcpng-lab", nodeName),
                "the plan must hold a reservation, or the reload below has nothing to lose");

        configureFromYaml();

        XcpngCloud after = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-lab");
        assertNotNull(after, "the reload must leave a cloud behind");
        assertNotEquals(
                System.identityHashCode(before),
                System.identityHashCode(after),
                "a reload is expected to rebuild the cloud; if it stopped doing so this test proves nothing");
        assertTrue(
                XcpngReservationStore.get().holds("xcpng-lab", nodeName),
                "a reload must not give back a slot the cloud had already committed to " + nodeName);
    }

    /**
     * The leaked-VM set must never appear in the exported YAML.
     *
     * <p>Round-tripping it through the document would fix #149 the wrong way round: a reference to a VM that
     * failed to die is runtime state, and JCasC's contract is that the YAML is what an operator wrote. The
     * store is a plain {@code Saveable} rather than a {@code GlobalConfiguration} precisely so no configurator
     * picks it up, and that is only true until someone changes its base class -- which is what this catches.
     */
    @Test
    void theLeakedVmStoreIsNotExportedToYaml(JenkinsRule r) throws Exception {
        configureFromYaml();
        XcpngCloud cloud = (XcpngCloud) r.jenkins.clouds.getByName("xcpng-lab");
        assertNotNull(cloud);
        cloud.recordLeakedVm("OpaqueRef:leaked-vm-1");
        assertFalse(cloud.leakedVmRefs().isEmpty(), "the leak must be recorded, or the assertions below are vacuous");

        String clouds = exportedClouds();
        assertFalse(
                clouds.contains("OpaqueRef:leaked-vm-1"), "runtime state must not reach the cloud export: " + clouds);
        assertFalse(clouds.contains("leakedVm"), "the cloud must not export a leaked-VM key at all: " + clouds);

        ConfigurationContext context = new ConfigurationContext(ConfiguratorRegistry.get());
        String root = toYamlString(getJenkinsRoot(context));
        assertFalse(root.contains("OpaqueRef:leaked-vm-1"), "runtime state must not reach any part of the export");
        assertFalse(
                root.toLowerCase(java.util.Locale.ROOT).contains("xcpngleakedvmstore"),
                "the store must not be a configuration-as-code root element");
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
