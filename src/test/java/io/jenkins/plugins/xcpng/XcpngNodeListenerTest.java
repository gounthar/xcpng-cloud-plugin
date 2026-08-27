package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.util.List;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The re-add race behind #145: an agent whose teardown has run must not survive being registered a second
 * time.
 *
 * <p>The second registration is core's, not ours. {@code NodeProvisioner} adds the node when it polls the
 * planned node's completed future, without checking that the node is still there, and the plugin has
 * already added it once so an inbound agent could dial in. On a short build the completion reap gets in
 * first, and the provisioner's add then resurrects a node whose VM is gone.
 *
 * <p>The tests drive that add directly rather than through a provisioner tick. What matters is the state
 * the listener has to recognise — a terminated agent arriving at {@code addNode} — and reproducing the
 * timing would only make the test slow and flaky without testing anything more.
 */
@WithJenkins
class XcpngNodeListenerTest {

    private static final String PINNED_FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

    private static final String POOL_URL = "https://pool.example.test";

    private static final String CREDENTIALS_ID = "cred";

    private static XcpngCloud cloudBackedBy(JenkinsRule r, FakeHypervisorClient fake) {
        XcpngCloud cloud =
                new XcpngCloud("xcpng", POOL_URL, CREDENTIALS_ID, PINNED_FINGERPRINT, 3, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    /**
     * An agent as the launcher leaves it: built by the cloud and holding a VM reference, without going
     * through a registration and a real launch. Setting the reference by hand is what keeps that true --
     * {@link XcpngLauncher} skips the clone when the agent already has one -- so registering this agent
     * touches the pool for nothing.
     */
    private static XcpngAgent agent(XcpngCloud cloud, String name) throws Exception {
        XcpngAgent agent = cloud.createAgent(
                LINUX_TEMPLATE, name, new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", name), false);
        agent.setVmRef("vm/" + name + "/1");
        return agent;
    }

    /**
     * The bug itself. Terminate the agent, which destroys the VM and removes the node, then add it back the
     * way the node provisioner does. Without the listener the node stays registered, offering an executor
     * against a VM that no longer exists until the idle net reclaims it {@code idleMinutes} later.
     */
    @Test
    void anAgentReRegisteredAfterItsTeardownIsRemovedAgain(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-1");
        r.jenkins.addNode(agent);
        assertNotNull(r.jenkins.getNode("xcpng-agent-1"), "the agent must be registered before it is torn down");

        agent.terminate();
        assertNull(r.jenkins.getNode("xcpng-agent-1"), "terminate must remove the node");
        assertTrue(agent.isTerminated(), "the teardown must mark the agent, or the listener has nothing to read");

        // What NodeProvisioner does on its next poll, having held this Node object since provisioning.
        r.jenkins.addNode(agent);

        assertNull(
                r.jenkins.getNode("xcpng-agent-1"),
                "a re-registered agent whose VM was already destroyed must not be left holding an executor");
    }

    /**
     * The guard has to be narrow. The same second add lands on every ordinary provision too, before any
     * teardown, and there it is the harmless no-op the provisioner intends — removing the node there would
     * destroy working agents mid-build, which is a far worse bug than the one being fixed.
     */
    @Test
    void anAgentThatHasNotBeenTornDownSurvivesBeingAddedTwice(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        XcpngAgent agent = agent(cloud, "xcpng-agent-2");

        r.jenkins.addNode(agent);
        r.jenkins.addNode(agent);

        assertFalse(agent.isTerminated(), "nothing has torn this agent down");
        assertNotNull(
                r.jenkins.getNode("xcpng-agent-2"),
                "the provisioner's second add is the normal case and must leave a live agent alone");
    }
}
