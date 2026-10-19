package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.slaves.SlaveComputer;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/**
 * The issued-agent record across a controller restart.
 *
 * <p>A reloaded agent is reclaimed within a moment of startup, so these tests read what its teardown leaves
 * behind rather than asking the agent afterwards. In the second session the cloud has no fake behind it and
 * no credential, so a teardown the guard lets through fails its destroy and records the VM as leaked; one
 * the guard refuses records nothing. Each test carries two nodes: the agent the plugin provisioned, and a
 * second written straight into {@code nodes/} with a VM handle of its own, which the plugin never issued.
 */
class XcpngIssuedAgentRestartTest {

    private static final XcpngTemplate TEMPLATE = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);
    private static final String NAME = "xcpng-agent-1";
    private static final String FOREIGN = "xcpng-foreign";
    private static final String FOREIGN_VM = "vm/foreign/1";

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void anUnissuedNodeStaysRefusedAcrossARestart() throws Throwable {
        String[] vmRef = new String[1];
        sessions.then(r -> vmRef[0] = provisionAndWriteForeign(r));
        sessions.then(r -> assertEquals(
                Set.of(vmRef[0]),
                leaksOnceReclaimed(r),
                "only the provisioned agent's" + " teardown may reach the pool; the foreign node's must be refused"));
    }

    @Test
    void anUpgradeTrustsWhatIsAlreadyOnDisk() throws Throwable {
        String[] vmRef = new String[1];
        sessions.then(r -> {
            vmRef[0] = provisionAndWriteForeign(r);
            // What a controller upgrading from a version without the store has: nodes, and no store file.
            File store = new File(r.jenkins.getRootDir(), XcpngIssuedAgentStore.class.getName() + ".xml");
            assertTrue(store.delete(), "the store file existed and is gone");
        });
        // The documented residual: the first start with the store cannot tell a node written before the upgrade
        // from one the plugin wrote, and trusts both, as every earlier version did.
        sessions.then(r -> assertEquals(Set.of(vmRef[0], FOREIGN_VM), leaksOnceReclaimed(r)));
    }

    @Test
    void anUnreadableStoreTrustsNothing() throws Throwable {
        sessions.then(r -> {
            provisionAndWriteForeign(r);
            File store = new File(r.jenkins.getRootDir(), XcpngIssuedAgentStore.class.getName() + ".xml");
            assertTrue(store.isFile(), "the store file exists before it is damaged");
            Files.writeString(
                    store.toPath(),
                    "<io.jenkins.plugins.xcpng.XcpngIssuedAgentStore><issuedByNode",
                    StandardCharsets.UTF_8);
        });
        // Unlike a missing store, a damaged one is not a first start: neither teardown may reach the pool.
        // anUnissuedNodeStaysRefusedAcrossARestart is the control, where the provisioned agent's does.
        sessions.then(r -> assertEquals(Set.of(), leaksOnceReclaimed(r)));
    }

    /** Provision one agent, then write a second, unissued node next to it on disk. Returns the agent's VM handle. */
    private static String provisionAndWriteForeign(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = new XcpngCloud("xcpng", "https://pool.example.test", "cred", null, 2, List.of(TEMPLATE));
        cloud.setClientFactory(c -> fake);
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);
        r.jenkins.save();
        XcpngAgent agent = cloud.createAgent(
                TEMPLATE, NAME, new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", NAME), false);
        r.jenkins.addNode(agent);
        long deadline = System.currentTimeMillis() + 30_000;
        while (agent.getVmRef() == null || agent.toComputer() instanceof SlaveComputer c && c.isConnecting()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the launcher never provisioned " + NAME);
            }
            Thread.sleep(50);
        }
        String foreign = Jenkins.XSTREAM2
                .toXML(agent)
                .replace("<name>" + NAME + "</name>", "<name>" + FOREIGN + "</name>")
                .replace("<vmRef>" + agent.getVmRef() + "</vmRef>", "<vmRef>" + FOREIGN_VM + "</vmRef>");
        assertTrue(foreign.contains(FOREIGN_VM), "the foreign node names its own VM");
        File dir = new File(r.jenkins.getRootDir(), "nodes/" + FOREIGN);
        assertTrue(dir.mkdirs());
        Files.writeString(new File(dir, "config.xml").toPath(), foreign, StandardCharsets.UTF_8);
        return agent.getVmRef();
    }

    /** Wait for the restart's reclaim of both nodes, then read which of their VMs were recorded as leaked. */
    private static Set<String> leaksOnceReclaimed(JenkinsRule r) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (r.jenkins.getNode(NAME) != null || r.jenkins.getNode(FOREIGN) != null) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the reloaded nodes were never reclaimed: " + r.jenkins.getNodes());
            }
            Thread.sleep(50);
        }
        return XcpngLeakedVmStore.get().refs("xcpng");
    }
}
