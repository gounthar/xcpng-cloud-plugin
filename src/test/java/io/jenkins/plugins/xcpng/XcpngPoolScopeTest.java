package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorException;
import io.jenkins.plugins.xcpng.client.PoolTrust;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A cloud scoped to one pool (#247): one Xen Orchestra appliance fronts several pools, and the same golden
 * image built on two of them is ambiguous unless the cloud says which pool it provisions into.
 */
@WithJenkins
class XcpngPoolScopeTest {

    private static final String POOL_A = "23ac115e-5f9a-0d96-c3b2-fb92f87fd1ec";
    private static final String POOL_B = "36f8ba18-927e-3ec5-06d2-1de822394115";
    private static final String TOKEN_ID = "xo-token";

    // -- the field -------------------------------------------------------

    @Test
    void thePoolIsStoredInTheFormXenOrchestraReportsIt(JenkinsRule r) {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://xo.example.test", TOKEN_ID, null, 2, List.of());
        assertNull(cloud.getPoolId(), "a new cloud resolves in every pool until told otherwise");
        cloud.setPoolId("  " + POOL_B.toUpperCase(java.util.Locale.ROOT) + " ");
        assertEquals(POOL_B, cloud.getPoolId());
        cloud.setPoolId("   ");
        assertNull(cloud.getPoolId(), "the form submits an empty string for an untouched field");
    }

    @Test
    void aConfigWrittenBeforeTheFieldLoadsWithNoPool(JenkinsRule r) {
        String xml = "<io.jenkins.plugins.xcpng.XcpngCloud>\n"
                + "  <name>xcpng</name>\n"
                + "  <poolUrl>https://xo.example.test</poolUrl>\n"
                + "  <maxInstances>2</maxInstances>\n"
                + "</io.jenkins.plugins.xcpng.XcpngCloud>\n";
        XcpngCloud cloud = (XcpngCloud) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
        assertNull(cloud.getPoolId(), "an existing cloud must keep provisioning exactly as it did");
    }

    /**
     * The form, not only the setter: {@code /manage/cloud/<name>/configure} rebuilds the cloud from what the
     * page submits, so a field the jelly does not carry is silently dropped by the first save.
     */
    @Test
    void aUiSaveOfTheCloudKeepsItsPool(JenkinsRule r) throws Exception {
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://xo.example.test", TOKEN_ID, null, 2, List.of());
        cloud.setPoolId(POOL_B);
        r.jenkins.clouds.add(cloud);

        r.submit(r.createWebClient().goTo("manage/cloud/xcpng/configure").getFormByName("config"));

        XcpngCloud saved = (XcpngCloud) r.jenkins.clouds.getByName("xcpng");
        assertTrue(saved != cloud, "a UI save is expected to rebuild the cloud; if not, this proves nothing");
        assertEquals(POOL_B, saved.getPoolId());
    }

    @Test
    void thePoolFieldAcceptsAUuidAndRefusesAName(JenkinsRule r) {
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolId(null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolId("").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckPoolId(" " + POOL_A.toUpperCase(java.util.Locale.ROOT)).kind);
        // A pool's name is what an operator sees first in the XO UI, and it is not what this field takes.
        FormValidation name = d.doCheckPoolId("xcp-ng-hhpfmhok");
        assertEquals(FormValidation.Kind.ERROR, name.kind);
        assertTrue(name.getMessage().contains("Test connection"), name.getMessage());
    }

    // -- Test connection -------------------------------------------------

    @Test
    void testConnectionNamesThePoolItResolvesIn(JenkinsRule r) {
        Map<String, String> pools = pools();
        FormValidation v = XcpngCloud.DescriptorImpl.connectedResult(PoolTrust.JVM_DEFAULT, POOL_B, pools);
        assertEquals(FormValidation.Kind.OK, v.kind);
        assertTrue(v.getMessage().contains("pool lab-98 (" + POOL_B + ")"), v.getMessage());
    }

    @Test
    void testConnectionListsThePoolsWhenNoneIsSetAndThereIsAChoice(JenkinsRule r) {
        FormValidation v = XcpngCloud.DescriptorImpl.connectedResult(PoolTrust.JVM_DEFAULT, null, pools());
        assertEquals(FormValidation.Kind.OK, v.kind);
        assertTrue(v.getMessage().contains("2 pools"), v.getMessage());
        assertTrue(v.getMessage().contains("lab-87 (" + POOL_A + ")"), v.getMessage());
        assertTrue(v.getMessage().contains("lab-98 (" + POOL_B + ")"), v.getMessage());
    }

    @Test
    void testConnectionSaysNothingAboutPoolsWhenThereIsOnlyOne(JenkinsRule r) {
        FormValidation v =
                XcpngCloud.DescriptorImpl.connectedResult(PoolTrust.JVM_DEFAULT, null, Map.of(POOL_A, "lab-87"));
        assertEquals(
                XcpngCloud.DescriptorImpl.connectedResult(PoolTrust.JVM_DEFAULT).getMessage(), v.getMessage());
    }

    // -- the template-name check -----------------------------------------

    @Test
    void theTemplateNameCheckAsksInThePoolTheFormNames(JenkinsRule r) {
        XcpngTemplate.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngTemplate.DescriptorImpl.class);
        List<String> asked = new ArrayList<>();
        d.setPoolProbe((poolUrl, credentialsId, certificateFingerprint, poolId) -> {
            asked.add(poolId);
            return new FakeHypervisorClient("golden");
        });
        d.doCheckTemplateName("golden", "https://xo.example.test", TOKEN_ID, null, null, POOL_B);
        assertEquals(List.of(POOL_B), asked, "the check must resolve where provisioning will, not everywhere");
    }

    // -- the real client, through the cloud ------------------------------

    /**
     * The cloud's own {@code openClient()} against a stub appliance with the same image in two pools: Test
     * connection and the template check open this one. Provisioning does not; it clones through the agent's
     * connection snapshot, which is the next test.
     */
    @Test
    void theCloudsClientResolvesInItsPoolAndRefusesAPoolTheTokenCannotSee(JenkinsRule r) throws Exception {
        HttpServer xo = stubAppliance();
        try {
            addToken();
            String url = "http://127.0.0.1:" + xo.getAddress().getPort();
            XcpngCloud cloud = new XcpngCloud("xcpng", url, TOKEN_ID, null, 2, List.of());

            try (HypervisorClient client = cloud.openClient()) {
                HypervisorException e = assertThrows(HypervisorException.class, () -> client.resolveTemplate("golden"));
                assertTrue(e.getMessage().contains("more than one pool"), e.getMessage());
            }

            cloud.setPoolId(POOL_B);
            try (HypervisorClient client = cloud.openClient()) {
                client.ping();
                assertEquals(POOL_B + "/tpl-b", client.resolveTemplate("golden").value());
            }

            cloud.setPoolId("00000000-0000-0000-0000-00000000000f");
            try (HypervisorClient client = cloud.openClient()) {
                HypervisorException e = assertThrows(HypervisorException.class, client::ping);
                assertTrue(e.getMessage().contains("is not visible to this token"), e.getMessage());
                assertTrue(e.getMessage().contains(POOL_A) && e.getMessage().contains(POOL_B), e.getMessage());
                assertFalse(e.getMessage().contains("null"), e.getMessage());
            }
        } finally {
            xo.stop(0);
        }
    }

    /**
     * The client provisioning actually clones through (#282): {@code provisionVm} opens it with {@code
     * agent.openClientForVm}, from the connection the agent snapshotted when it was built. That snapshot used
     * to carry no pool, so a cloud scoped to one pool resolved its template in every pool and was refused as
     * ambiguous on the first launch, on the lab as here. The test above went through {@code
     * cloud.openClient()} and passed throughout.
     *
     * <p>The cloud is repointed after the agent is built, as for the backend: the VM is cloned and destroyed
     * against the pool the agent was created for, not wherever the cloud points by then.
     */
    @Test
    void provisioningResolvesInThePoolTheAgentWasBuiltFor(JenkinsRule r) throws Exception {
        HttpServer xo = stubAppliance();
        try {
            addToken();
            String url = "http://127.0.0.1:" + xo.getAddress().getPort();
            XcpngTemplate template = new XcpngTemplate("golden", "xcpng", 1, 1024);
            XcpngCloud cloud = new XcpngCloud("xcpng", url, TOKEN_ID, null, 2, List.of(template));
            cloud.setPoolId(POOL_B);
            r.jenkins.clouds.add(cloud);
            XcpngAgent agent = cloud.createAgent(
                    template,
                    "xcpng-golden-1",
                    new org.jenkinsci.plugins.cloudstats.ProvisioningActivity.Id("xcpng", "golden", "xcpng-golden-1"),
                    false);

            cloud.setPoolId(POOL_A);

            assertEquals(POOL_B, agent.getPoolId(), "the agent must snapshot the cloud's pool when it is built");
            try (HypervisorClient client = agent.openClientForVm(cloud)) {
                assertEquals(
                        POOL_B + "/tpl-b",
                        client.resolveTemplate("golden").value(),
                        "the clone must resolve in the agent's pool, not in every pool and not in the cloud's new one");
            }
        } finally {
            xo.stop(0);
        }
    }

    private static Map<String, String> pools() {
        Map<String, String> pools = new LinkedHashMap<>();
        pools.put(POOL_A, "lab-87");
        pools.put(POOL_B, "lab-98");
        return pools;
    }

    private static void addToken() throws IOException {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, TOKEN_ID, "xo token", Secret.fromString("not-a-real-token")));
        SystemCredentialsProvider.getInstance().save();
    }

    /** The three routes the path under test reads, answering as the lab appliance does (2026-09-27). */
    private static HttpServer stubAppliance() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/rest/v0/pools",
                exchange -> reply(
                        exchange,
                        200,
                        "[{\"id\":\"" + POOL_A + "\",\"name_label\":\"lab-87\"}," + "{\"id\":\"" + POOL_B
                                + "\",\"name_label\":\"lab-98\"}]"));
        server.createContext(
                "/rest/v0/vm-templates",
                exchange -> reply(
                        exchange,
                        200,
                        "[{\"id\":\"tpl-a\",\"uuid\":\"tpl-a\",\"name_label\":\"golden\",\"$pool\":\"" + POOL_A + "\"},"
                                + "{\"id\":\"tpl-b\",\"uuid\":\"tpl-b\",\"name_label\":\"golden\",\"$pool\":\"" + POOL_B
                                + "\"}]"));
        String probe = "00000000-0000-0000-0000-000000000000";
        server.createContext(
                "/rest/v0/vms/" + probe,
                exchange -> reply(
                        exchange,
                        404,
                        "{\"error\":\"no such VM " + probe + "\",\"data\":{\"id\":\"" + probe
                                + "\",\"type\":\"VM\"}}"));
        server.start();
        return server;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The template-name check verifies the appliance the way the form says to, CA certificates included (#172). */
    @Test
    void theTemplateNameCheckCarriesTheCaCertificates(JenkinsRule r) {
        XcpngTemplate.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngTemplate.DescriptorImpl.class);
        List<PoolTrust> asked = new ArrayList<>();
        d.setPoolProbe((poolUrl, credentialsId, trust, poolId) -> {
            asked.add(trust);
            return new FakeHypervisorClient("golden");
        });
        d.doCheckTemplateName("golden", "https://xo.example.test", TOKEN_ID, null, "PEM\r\nBODY\r\n", null);
        assertEquals(List.of(PoolTrust.anchoredAt("PEM\nBODY")), asked);
    }
}
