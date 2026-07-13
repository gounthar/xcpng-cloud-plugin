package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The provisioning half against an in-memory {@link FakeHypervisorClient}: that provisioning clones
 * and starts a VM, that terminating the agent destroys the VM with its disks, and that label matching
 * and the instance cap gate what is provisioned. The real teardown ordering (VBDs, VDIs, then the VM)
 * is covered against JSON fixtures in the client's own tests, not here.
 */
@WithJenkins
class XcpngProvisionTest {

    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 1, 2, 2048);

    /** A cloud whose clients are the given fake, so a test can inspect the recorded call sequence. */
    private static XcpngCloud cloudBackedBy(FakeHypervisorClient fake, int maxInstances) {
        XcpngCloud cloud = new XcpngCloud(
                "xcpng", "https://pool.example.test", "cred", false, maxInstances, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        return cloud;
    }

    @Test
    void provisionClonesStartsAndWrapsTheVmInAnAgent(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        Node node = cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1");

        XcpngAgent agent = assertInstanceOf(XcpngAgent.class, node);
        assertEquals("vm/xcpng-agent-1/1", agent.getVmRef());
        assertEquals(
                List.of(
                        "resolveTemplate:jenkins-golden-debian",
                        "cloneFromTemplate:template/jenkins-golden-debian->vm/xcpng-agent-1/1",
                        "start:vm/xcpng-agent-1/1",
                        "close"),
                fake.calls());
    }

    @Test
    void terminatingTheAgentDestroysTheVmWithDisks(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        XcpngAgent agent = (XcpngAgent) cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1");
        r.jenkins.addNode(agent);
        agent.terminate();

        assertTrue(
                fake.calls().contains("destroyWithDisks:vm/xcpng-agent-1/1"),
                "termination must destroy the backing VM: " + fake.calls());
        // The VM must be started before it is destroyed, never the other way round.
        assertTrue(
                fake.calls().indexOf("start:vm/xcpng-agent-1/1")
                        < fake.calls().indexOf("destroyWithDisks:vm/xcpng-agent-1/1"),
                fake.calls().toString());
    }

    @Test
    void canProvisionOnlyForAMatchingLabelWithinCap(JenkinsRule r) {
        XcpngCloud cloud = cloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 1);
        r.jenkins.clouds.add(cloud);

        assertTrue(cloud.canProvision(new Cloud.CloudState(Label.get("xcpng-linux"), 0)));
        assertFalse(cloud.canProvision(new Cloud.CloudState(Label.get("windows"), 0)));
    }

    @Test
    void provisionIsBoundedByTheInstanceCap(JenkinsRule r) {
        XcpngCloud cloud = cloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 2);
        r.jenkins.clouds.add(cloud);

        // Five executors' worth of demand, one executor per node, cap of two: only two nodes planned.
        Collection<NodeProvisioner.PlannedNode> planned =
                cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 5);
        assertEquals(2, planned.size());
    }

    @Test
    void provisionSkipsAnUnmatchedLabel(JenkinsRule r) {
        XcpngCloud cloud = cloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 2);
        r.jenkins.clouds.add(cloud);

        assertTrue(cloud.provision(new Cloud.CloudState(Label.get("windows"), 0), 3)
                .isEmpty());
    }
}
