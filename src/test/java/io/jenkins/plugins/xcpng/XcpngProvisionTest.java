package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.slaves.JnlpAgentReceiver;
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
        // The fake agents never connect, so skip the online wait; these tests assert planning/capacity,
        // which the production online-wait does not change.
        cloud.setWaitForOnline(false);
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
    void provisionSeedsGuestDataForTheInboundConnection(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);
        // Pin an explicit root URL so the url assertion compares a known value rather than relying on
        // whatever the harness happens to set (and never degrading to null == null).
        JenkinsLocationConfiguration.get().setUrl("https://controller.example.test/");

        cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1");

        Map<String, String> seed = fake.lastSpec().guestData();
        // The guest reads these three keys to dial back in as an inbound agent: the controller URL, the
        // node name it registers as, and the JNLP secret, which is a stable HMAC of that name computable
        // before the node exists. Assert the secret matches what a real inbound agent would present.
        assertEquals("xcpng-agent-1", seed.get("name"));
        assertEquals(JnlpAgentReceiver.SLAVE_SECRET.mac("xcpng-agent-1"), seed.get("secret"));
        assertEquals("https://controller.example.test/", seed.get("url"));
        // The default template sets no SSH key, so an inbound-only clone stays key-free.
        assertFalse(seed.containsKey("ssh_authorized_key"), "no SSH key configured means none in the seed");
    }

    @Test
    void sshKeyValidatorAcceptsOneKeyAndRejectsBlocksPrivateKeysAndDsa(JenkinsRule r) {
        XcpngTemplate.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngTemplate.DescriptorImpl.class);
        // Optional field: blank is fine.
        assertEquals(FormValidation.Kind.OK, d.doCheckSshAuthorizedKey("  ").kind);
        // A single public key on one line is accepted.
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckSshAuthorizedKey("ssh-ed25519 AAAAC3NzaExampleKey operator@host").kind);
        // A block of keys (embedded newline) is rejected, so only one line ever reaches authorized_keys.
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckSshAuthorizedKey("ssh-ed25519 AAAAOne a@h\nssh-ed25519 AAAATwo b@h").kind);
        // A pasted private key is rejected outright.
        assertEquals(
                FormValidation.Kind.ERROR, d.doCheckSshAuthorizedKey("-----BEGIN OPENSSH PRIVATE KEY-----").kind);
        // Legacy DSA is rejected: current OpenSSH will not authenticate it.
        assertEquals(FormValidation.Kind.ERROR, d.doCheckSshAuthorizedKey("ssh-dss AAAADsaExample x@h").kind);
    }

    @Test
    void provisionSeedsTheOptionalSshKeyWhenTheTemplateHasOne(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        XcpngTemplate keyed = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 1, 2, 2048);
        String pubkey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyForTest operator@host";
        keyed.setSshAuthorizedKey(pubkey);

        cloud.provisionNode(keyed, "xcpng-agent-1");

        assertEquals(pubkey, fake.lastSpec().guestData().get("ssh_authorized_key"));
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
    void aCloneThatFailsToStartIsDestroyedNotLeaked(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").failStart();
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        // Narrowed to the client's own exception (not any Exception): the clone succeeds and only the
        // start throws, so a broader assertion could pass on an unrelated failure and hide a regression.
        HypervisorException thrown = assertThrows(
                HypervisorException.class, () -> cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1"));
        assertTrue(
                thrown.getMessage().contains("start failed"),
                "the surfaced failure must be the start failure: " + thrown.getMessage());
        assertTrue(
                fake.calls().contains("destroyWithDisks:vm/xcpng-agent-1/1"),
                "a clone that fails to start must be destroyed, not leaked: " + fake.calls());
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

    @Test
    void aRejectedProvisionSubmitReleasesItsReservation(JenkinsRule r) {
        XcpngCloud cloud = cloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 2);
        r.jenkins.clouds.add(cloud);
        // A shut-down executor rejects every submit, standing in for the remoting pool during shutdown.
        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdownNow();
        cloud.setProvisionExecutor(dead);

        Collection<NodeProvisioner.PlannedNode> planned =
                cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 5);

        assertTrue(planned.isEmpty(), "a rejected submit plans nothing");
        assertEquals(0, cloud.inFlightCount(), "the reservation must be released when the submit is rejected");
    }

    @Test
    void inFlightReservationsHoldTheCapAcrossRounds(JenkinsRule r) throws Exception {
        XcpngCloud cloud = cloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 2);
        r.jenkins.clouds.add(cloud);
        // One worker thread, blocked on a gate, so the provisioning tasks queue and their reservations
        // stay outstanding while we inspect the cap. A second round must see no free capacity.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        exec.submit(() -> {
            gate.await();
            return null;
        });
        cloud.setProvisionExecutor(exec);
        try {
            Collection<NodeProvisioner.PlannedNode> first =
                    cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 5);
            Collection<NodeProvisioner.PlannedNode> second =
                    cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 5);

            assertEquals(2, first.size());
            assertEquals(0, second.size(), "the cap must hold while earlier reservations are outstanding");
            assertEquals(2, cloud.inFlightCount());
        } finally {
            gate.countDown();
            exec.shutdownNow();
        }
    }

    @Test
    void cancellingAPlannedNodeReleasesItsReservation(JenkinsRule r) throws Exception {
        XcpngCloud cloud = cloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 2);
        r.jenkins.clouds.add(cloud);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        exec.submit(() -> {
            gate.await();
            return null;
        });
        cloud.setProvisionExecutor(exec);
        try {
            Collection<NodeProvisioner.PlannedNode> planned =
                    cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 1);
            assertEquals(1, planned.size());
            assertEquals(1, cloud.inFlightCount());

            // The node provisioner can cancel a planned node; that must not strand the reservation.
            planned.iterator().next().future.cancel(true);

            assertEquals(0, cloud.inFlightCount(), "cancelling a planned node must release its reservation");
        } finally {
            gate.countDown();
            exec.shutdownNow();
        }
    }
}
