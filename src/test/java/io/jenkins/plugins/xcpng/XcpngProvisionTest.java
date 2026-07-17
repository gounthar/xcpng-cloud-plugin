package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.slaves.JnlpAgentReceiver;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
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
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

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

    /** The cloud-stats activity id a real provision would build for {@code nodeName} under this cloud. */
    private static ProvisioningActivity.Id activityId(String nodeName) {
        return new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", nodeName);
    }

    @Test
    void provisionClonesStartsAndWrapsTheVmInAnAgent(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        Node node = cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"));

        XcpngAgent agent = assertInstanceOf(XcpngAgent.class, node);
        assertEquals("vm/xcpng-agent-1/1", agent.getVmRef());
        assertFalse(agent.isWarm(), "an on-demand agent is used from birth, never a warm spare");
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

        cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"));

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
    void provisionMarksTheCloneWithTheOwningCloud(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"));

        // The recovery contract: every clone carries the owning cloud's name on its VM record, so a VM the
        // plugin lost track of (a controller that died mid-provision, a destroy that threw) is still findable
        // by tools/reaper.py. The reaper matched on names before, and the plugin's names never matched.
        assertEquals("xcpng", fake.lastSpec().owner(), "a provisioned clone must be marked with its cloud");
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
        assertEquals(FormValidation.Kind.ERROR, d.doCheckSshAuthorizedKey("-----BEGIN OPENSSH PRIVATE KEY-----").kind);
        // Legacy DSA is rejected: current OpenSSH will not authenticate it.
        assertEquals(FormValidation.Kind.ERROR, d.doCheckSshAuthorizedKey("ssh-dss AAAADsaExample x@h").kind);
    }

    @Test
    void provisionSeedsTheOptionalSshKeyWhenTheTemplateHasOne(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        XcpngTemplate keyed = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);
        String pubkey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyForTest operator@host";
        keyed.setSshAuthorizedKey(pubkey);

        cloud.provisionNode(keyed, "xcpng-agent-1", activityId("xcpng-agent-1"));

        assertEquals(pubkey, fake.lastSpec().guestData().get("ssh_authorized_key"));
    }

    @Test
    void terminatingTheAgentDestroysTheVmWithDisks(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        XcpngAgent agent =
                (XcpngAgent) cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"));
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
                HypervisorException.class,
                () -> cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1")));
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
    void provisionPlansTrackedNodesCarryingTheActivityId(JenkinsRule r) {
        XcpngCloud cloud = cloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 2);
        r.jenkins.clouds.add(cloud);

        // cloud-stats reads the phases off a TrackedPlannedNode; a plain PlannedNode would never be tracked.
        Collection<NodeProvisioner.PlannedNode> planned =
                cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 1);
        assertEquals(1, planned.size());

        TrackedPlannedNode tracked =
                assertInstanceOf(TrackedPlannedNode.class, planned.iterator().next());
        ProvisioningActivity.Id id = tracked.getId();
        assertEquals("xcpng", id.getCloudName());
        assertEquals("jenkins-golden-debian", id.getTemplateName());
        // The node name is the generated display name the clone registers under, which cloud-stats shows.
        assertTrue(
                id.getNodeName().startsWith("xcpng-jenkins-golden-debian-"),
                "the activity node name must be the clone's display name: " + id.getNodeName());
    }

    @Test
    void theAgentAndItsComputerReportTheSameActivity(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        // The exact id the planned node would carry. cloud-stats' id equality is per-instance (a random
        // fingerprint), so correlation works only if the agent holds this same object, not a rebuilt one.
        ProvisioningActivity.Id id = activityId("xcpng-agent-1");
        XcpngAgent agent = (XcpngAgent) cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", id);
        assertSame(id, agent.getId(), "the agent must carry the very id instance it was provisioned with");

        r.jenkins.addNode(agent);
        Computer computer = agent.toComputer();
        TrackedItem trackedComputer = assertInstanceOf(TrackedItem.class, computer);
        // Equal (by fingerprint) so cloud-stats moves this one activity through its launching/operating phases.
        assertEquals(id, trackedComputer.getId(), "the computer must report the agent's activity");
    }

    @Test
    void onlineScrubsTheSeedSecretFromTheVmRecord(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);
        XcpngAgent agent =
                (XcpngAgent) cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"));
        r.jenkins.addNode(agent);
        Computer computer = agent.toComputer();

        // The fake agent never really connects, so drive the connect hook directly: onOnline is what fires
        // once an inbound agent has read the seed and no longer needs the secret in the VM record.
        new XcpngComputerListener().onOnline(computer, hudson.model.TaskListener.NULL);

        assertTrue(
                fake.calls().contains("clearGuestSecret:" + agent.getVmRef()),
                "coming online must scrub the seed secret for the agent's VM, calls were " + fake.calls());
    }

    @Test
    void onlineIgnoresANonXcpngComputer(JenkinsRule r) throws Exception {
        // A listener firing for some other cloud's computer must not open a client or touch a VM. Guard it
        // with the built-in master computer, which is never an XcpngComputer.
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        new XcpngComputerListener().onOnline(r.jenkins.toComputer(), hudson.model.TaskListener.NULL);

        assertTrue(
                fake.calls().isEmpty(), "a non-XCP-ng computer must not reach the client, calls were " + fake.calls());
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

    @Test
    void interruptingAProvisionStillDestroysTheVmAndKeepsTheInterrupt(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud =
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", false, 2, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        // Unlike the other tests, keep the production online wait, so the task is still running when the
        // interrupt lands. Once the node is registered the interrupt may land anywhere -- in addNode's tail
        // or in awaitOnline's sleep -- and every one of those must still destroy the VM, so the assertions
        // below hold regardless of where it hits rather than depending on one landing spot.
        r.jenkins.clouds.add(cloud);

        // afterExecute runs on the worker right after the task body, so it observes the interrupt flag the
        // task left behind -- the only vantage point from which "restored on the way out" is assertable.
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean interruptSurvived = new AtomicBoolean();
        CountDownLatch taskDone = new CountDownLatch(1);
        ThreadPoolExecutor exec =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                    Thread t = new Thread(runnable, "provision-under-test");
                    worker.set(t);
                    return t;
                }) {
                    @Override
                    protected void afterExecute(Runnable runnable, Throwable thrown) {
                        interruptSurvived.set(Thread.currentThread().isInterrupted());
                        taskDone.countDown();
                    }
                };
        cloud.setProvisionExecutor(exec);
        try {
            NodeProvisioner.PlannedNode planned = cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 1)
                    .iterator()
                    .next();

            // Interrupt only once the node is registered, which puts the task past the clone/start calls:
            // interrupting earlier would fail the clone instead, leaving no VM to clean up and testing a
            // different path. Spin rather than sleep between checks, so the interrupt lands as close to
            // addNode as possible -- that window, not awaitOnline's sleep, is where the flag can still be
            // set when the exception surfaces, and it is the case a coarser poll would rarely sample.
            long deadline = System.currentTimeMillis() + 30_000;
            while (r.jenkins.getNodes().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(r.jenkins.getNodes().isEmpty(), "the provision should have registered its node");
            worker.get().interrupt();

            assertTrue(taskDone.await(30, TimeUnit.SECONDS), "the interrupted provision should finish");
            assertThrows(Exception.class, () -> planned.future.get(30, TimeUnit.SECONDS));
            assertTrue(
                    fake.calls().stream().anyMatch(c -> c.startsWith("destroyWithDisks:")),
                    "an interrupted provision must still destroy its VM rather than leak it: " + fake.calls());
            assertTrue(interruptSurvived.get(), "the interrupt must be restored once the cleanup has run");
        } finally {
            exec.shutdownNow();
        }
    }

    // ---- Warm pool (slice C) ----

    /** A warm-pool template for {@code templateName}, wanting {@code minInstances} spares. */
    private static XcpngTemplate warmTemplate(String templateName, int minInstances) {
        XcpngTemplate template = new XcpngTemplate(templateName, "xcpng-linux", 2, 2048);
        template.setMinInstances(minInstances);
        return template;
    }

    /** A cloud over the given warm-pool templates, backed by the given fake. */
    private static XcpngCloud warmCloudOver(FakeHypervisorClient fake, int maxInstances, XcpngTemplate... templates) {
        XcpngCloud cloud =
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", false, maxInstances, List.of(templates));
        cloud.setClientFactory(c -> fake);
        cloud.setWaitForOnline(false);
        return cloud;
    }

    /** A cloud whose single template keeps {@code minInstances} warm spares, backed by the given fake. */
    private static XcpngCloud warmCloudBackedBy(FakeHypervisorClient fake, int maxInstances, int minInstances) {
        return warmCloudOver(fake, maxInstances, warmTemplate("jenkins-golden-debian", minInstances));
    }

    /**
     * Reconcile the warm pool with both halves on controllable workers, then wait for the launches to
     * register and the drains to tear down, so the node list and the fake's call log are settled by the
     * time the caller asserts on them.
     */
    private static void reconcileAndSettle(XcpngCloud cloud) throws InterruptedException {
        ExecutorService launches = Executors.newSingleThreadExecutor();
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        cloud.setProvisionExecutor(launches);
        cloud.setReapExecutor(reaps);
        cloud.reconcileWarmPool();
        launches.shutdown();
        assertTrue(launches.awaitTermination(30, TimeUnit.SECONDS), "the warm-pool launches should finish");
        reaps.shutdown();
        assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "the warm-pool drains should finish");
    }

    private static long destroyCount(FakeHypervisorClient fake) {
        return fake.calls().stream()
                .filter(c -> c.startsWith("destroyWithDisks:"))
                .count();
    }

    private static int warmNodeCount(JenkinsRule r) {
        int count = 0;
        for (Node node : r.jenkins.getNodes()) {
            if (node instanceof XcpngAgent agent && agent.isWarm()) {
                count++;
            }
        }
        return count;
    }

    @Test
    void warmPoolFillsToMinInstances(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = warmCloudBackedBy(fake, 3, 2);

        reconcileAndSettle(cloud);

        assertEquals(2, warmNodeCount(r), "the pool should fill to minInstances");
        for (Node node : r.jenkins.getNodes()) {
            XcpngAgent agent = assertInstanceOf(XcpngAgent.class, node);
            assertTrue(agent.isWarm(), "a maintainer-launched agent is a warm spare");
            assertNotNull(agent.getId(), "the spare carries a cloud-stats activity");
            assertEquals("jenkins-golden-debian", agent.getId().getTemplateName());
        }
    }

    @Test
    void warmPoolIsClampedByTheCap(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = warmCloudBackedBy(fake, 2, 5); // wants five, cap of two

        reconcileAndSettle(cloud);

        assertEquals(2, warmNodeCount(r), "the warm pool cannot exceed the instance cap");
    }

    @Test
    void warmReconcileIsIdempotentAcrossTicks(JenkinsRule r) throws Exception {
        XcpngCloud cloud = warmCloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 3, 2);
        // One worker blocked on a gate, so the first tick's launches queue with their reservations
        // outstanding and never register. A second tick must see the deficit already covered and add nothing.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        exec.submit(() -> {
            gate.await();
            return null;
        });
        cloud.setProvisionExecutor(exec);
        try {
            cloud.reconcileWarmPool();
            cloud.reconcileWarmPool();
            assertEquals(2, cloud.inFlightCount(), "the second tick must not double-provision the pool");
        } finally {
            gate.countDown();
            exec.shutdownNow();
        }
    }

    // ---- Warm pool (slice D): draining a surplus ----

    @Test
    void loweringTheTargetDrainsTheSurplusSpares(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngTemplate template = warmTemplate("jenkins-golden-debian", 3);
        XcpngCloud cloud = warmCloudOver(fake, 3, template);
        r.jenkins.clouds.add(cloud);

        reconcileAndSettle(cloud);
        assertEquals(3, warmNodeCount(r), "the pool should fill to the original target");

        // The administrator lowers the target. Spares are exempt from the idle reap, so without the drain
        // the surplus two would run until they each happened to pick up a build.
        template.setMinInstances(1);
        reconcileAndSettle(cloud);

        assertEquals(1, warmNodeCount(r), "the surplus spares should be drained down to the new target");
        assertEquals(1, r.jenkins.getNodes().size(), "the drained spares should be removed as nodes");
        assertEquals(2, destroyCount(fake), "each drained spare must destroy its VM: " + fake.calls());
    }

    @Test
    void aSpareWhoseTemplateWasRemovedIsDrained(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud before = warmCloudOver(fake, 3, warmTemplate("jenkins-golden-debian", 1));
        r.jenkins.clouds.add(before);

        reconcileAndSettle(before);
        assertEquals(1, warmNodeCount(r), "the pool should fill its one spare");

        // The administrator drops that template and configures a different one. Editing the cloud replaces
        // the instance under the same name, which is what the running spare still points at by cloudName.
        XcpngCloud after = warmCloudOver(fake, 3, warmTemplate("jenkins-golden-other", 0));
        r.jenkins.clouds.remove(before);
        r.jenkins.clouds.add(after);

        reconcileAndSettle(after);

        assertEquals(0, warmNodeCount(r), "a spare for a removed template belongs to nothing and must be drained");
        assertEquals(1, destroyCount(fake), "the orphaned spare must destroy its VM: " + fake.calls());
    }

    @Test
    void theDrainLeavesAUsedAgentAlone(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngTemplate template = warmTemplate("jenkins-golden-debian", 1);
        XcpngCloud cloud = warmCloudOver(fake, 3, template);
        r.jenkins.clouds.add(cloud);

        reconcileAndSettle(cloud);
        assertEquals(1, warmNodeCount(r));

        // The spare accepts a build, so it is no longer a spare: it is an ordinary single-use agent, which
        // the retention strategy reaps once that build finishes. The drain must not reach it even with the
        // target dropped to zero underneath it -- yanking it would kill the build it is running.
        XcpngAgent agent =
                assertInstanceOf(XcpngAgent.class, r.jenkins.getNodes().get(0));
        agent.markUsed();
        template.setMinInstances(0);
        reconcileAndSettle(cloud);

        assertEquals(1, r.jenkins.getNodes().size(), "a used agent is not the warm pool's to drain");
        assertEquals(0, destroyCount(fake), "a used agent must not be torn down by the drain: " + fake.calls());
    }

    @Test
    void aTemplateAtItsTargetIsNeitherFilledNorDrained(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = warmCloudBackedBy(fake, 3, 2);
        r.jenkins.clouds.add(cloud);

        reconcileAndSettle(cloud);
        assertEquals(2, warmNodeCount(r), "the pool should fill to minInstances");

        // Steady state: the two halves must not fight, launching a spare the drain then reaps (or the
        // reverse) and churning VMs on the pool every tick.
        reconcileAndSettle(cloud);

        assertEquals(2, warmNodeCount(r), "a pool at its target must be left alone");
        assertEquals(2, r.jenkins.getNodes().size());
        assertEquals(0, destroyCount(fake), "nothing should be torn down at the target: " + fake.calls());
    }

    @Test
    void theDrainAndTheIdleNetNeverBothDestroyTheSameSpare(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngTemplate template = warmTemplate("jenkins-golden-debian", 1);
        XcpngCloud cloud = warmCloudOver(fake, 3, template);
        r.jenkins.clouds.add(cloud);
        reconcileAndSettle(cloud);

        XcpngAgent spare =
                assertInstanceOf(XcpngAgent.class, r.jenkins.getNodes().get(0));
        AbstractCloudComputer<?> computer = assertInstanceOf(AbstractCloudComputer.class, spare.getComputer());
        XcpngRetentionStrategy strategy =
                assertInstanceOf(XcpngRetentionStrategy.class, computer.getRetentionStrategy());

        // This spare never came online, so it keeps no idle exemption and the retention net may reclaim it
        // at any moment -- the same spare a shrinking target makes surplus. That overlap is the real race,
        // not a contrived one. Let the idle net win, and hold its teardown mid-flight behind a gate.
        ExecutorService reaps = Executors.newSingleThreadExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        reaps.submit(() -> {
            gate.await();
            return null;
        });
        strategy.reap(computer, reaps);

        // Now lower the target, so the drain picks that very spare while the first teardown is still queued.
        template.setMinInstances(0);
        cloud.setReapExecutor(reaps);
        cloud.reconcileWarmPool();

        gate.countDown();
        reaps.shutdown();
        assertTrue(reaps.awaitTermination(30, TimeUnit.SECONDS), "the teardown should finish");

        // One VM, one destroy. Two would mean the drain fired destroyWithDisks at a VM the idle net was
        // already destroying, which is what routing both through the strategy's guard exists to prevent.
        assertEquals(
                1, destroyCount(fake), "a spare must be destroyed once, not once per reclaim route: " + fake.calls());
    }

    @Test
    void warmMaintainerExtensionIsRegistered(JenkinsRule r) {
        assertEquals(
                1,
                r.jenkins.getExtensionList(XcpngWarmPoolMaintainer.class).size(),
                "the warm-pool maintainer should be an active periodic task");
    }

    // ---- Warm pool (slice C): steady state ----

    @Test
    void aConsumedSpareIsRefilledOnTheNextTick(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = warmCloudBackedBy(fake, 3, 2); // cap 3, target 2
        r.jenkins.clouds.add(cloud);

        reconcileAndSettle(cloud);
        assertEquals(2, warmNodeCount(r), "the pool should fill to minInstances");

        // One spare accepts a build, so it stops counting as warm: the deficit reopens and the next tick must
        // boot a replacement. This is the refill contract. Counting all agents of the template rather than only
        // the warm ones would see three, find no deficit, and never replenish while used agents finish builds.
        XcpngAgent consumed = null;
        for (Node node : r.jenkins.getNodes()) {
            if (node instanceof XcpngAgent agent && agent.isWarm()) {
                consumed = agent;
                break;
            }
        }
        assertNotNull(consumed, "there should be a warm spare to consume");
        consumed.markUsed();

        reconcileAndSettle(cloud);

        assertEquals(2, warmNodeCount(r), "the consumed spare must be replaced so the warm count returns to target");
        assertEquals(3, r.jenkins.getNodes().size(), "the used agent stays; two fresh spares sit beside it");
    }

    /** An executor whose every submit throws, standing in for any RuntimeException escaping a reconcile. */
    private static ExecutorService throwingExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                throw new IllegalStateException("reconcile boom");
            }

            @Override
            public void shutdown() {}

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }

    @Test
    void theMaintainerIsolatesOneCloudsFailureFromTheRest(JenkinsRule r) throws Exception {
        // The first cloud throws synchronously from its reconcile (its provision submit blows up); the second
        // is healthy. The maintainer's per-cloud try/catch must let the healthy cloud's spares launch anyway.
        // Narrow or remove that catch and the first cloud's RuntimeException aborts the whole tick, starving
        // every other cloud's warm pool -- and nothing else in the suite would notice.
        FakeHypervisorClient brokenFake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud broken = new XcpngCloud(
                "xcpng-a",
                "https://pool.example.test",
                "cred",
                false,
                3,
                List.of(warmTemplate("jenkins-golden-debian", 1)));
        broken.setClientFactory(c -> brokenFake);
        broken.setWaitForOnline(false);
        broken.setProvisionExecutor(throwingExecutor());
        r.jenkins.clouds.add(broken);

        FakeHypervisorClient healthyFake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud healthy = new XcpngCloud(
                "xcpng-b",
                "https://pool.example.test",
                "cred",
                false,
                3,
                List.of(warmTemplate("jenkins-golden-debian", 1)));
        healthy.setClientFactory(c -> healthyFake);
        healthy.setWaitForOnline(false);
        ExecutorService launches = Executors.newSingleThreadExecutor();
        healthy.setProvisionExecutor(launches);
        r.jenkins.clouds.add(healthy);

        new XcpngWarmPoolMaintainer().execute(TaskListener.NULL);

        launches.shutdown();
        assertTrue(launches.awaitTermination(30, TimeUnit.SECONDS), "the healthy cloud's launch should finish");
        assertEquals(1, warmNodeCount(r), "the healthy cloud's spare must launch despite the broken cloud throwing");
    }
}
