package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.slaves.JnlpAgentReceiver;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
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
    void sshKeySetterEnforcesTheSingleKeyInvariantThatTheValidatorOnlyAdvises(JenkinsRule r) {
        XcpngTemplate t = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);
        // A single valid public key is accepted and stored trimmed.
        t.setSshAuthorizedKey("  ssh-ed25519 AAAAValidKey operator@host  ");
        assertEquals("ssh-ed25519 AAAAValidKey operator@host", t.getSshAuthorizedKey());
        // Blank collapses to null, the inbound-only default.
        t.setSshAuthorizedKey("   ");
        assertNull(t.getSshAuthorizedKey());
        // doCheckSshAuthorizedKey is advisory, so the form saves past a red message; the setter is where
        // the rule bites. A multi-line value (smuggling extra keys or option prefixes), a pasted private
        // key, and a non-pubkey prefix each throw rather than being seeded verbatim to every clone.
        assertThrows(
                IllegalArgumentException.class,
                () -> t.setSshAuthorizedKey("ssh-ed25519 AAAAOne a@h\nssh-ed25519 AAAATwo b@h"));
        assertThrows(
                IllegalArgumentException.class, () -> t.setSshAuthorizedKey("-----BEGIN OPENSSH PRIVATE KEY-----"));
        assertThrows(IllegalArgumentException.class, () -> t.setSshAuthorizedKey("ssh-dss AAAADsaExample x@h"));
    }

    @Test
    void readResolveDropsAndLogsAMultilineSshKeyFromAHandEditedConfig(JenkinsRule r) {
        Logger logger = Logger.getLogger(XcpngTemplate.class.getName());
        List<LogRecord> records = new ArrayList<>();
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
        logger.addHandler(handler);
        XcpngTemplate t;
        try {
            // &#10; is an XML newline in the element text, so the deserialized field carries an embedded
            // line break exactly as a hand-edited config.xml smuggling a second key would.
            String xml = "<io.jenkins.plugins.xcpng.XcpngTemplate>\n"
                    + "  <templateName>jenkins-golden-debian</templateName>\n"
                    + "  <labelString>xcpng-linux</labelString>\n"
                    + "  <sshAuthorizedKey>ssh-ed25519 AAAAOne a@h&#10;ssh-ed25519 AAAATwo b@h</sshAuthorizedKey>\n"
                    + "</io.jenkins.plugins.xcpng.XcpngTemplate>\n";
            t = (XcpngTemplate) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);
        } finally {
            logger.removeHandler(handler);
        }
        assertNull(t.getSshAuthorizedKey(), "a multi-line key from a hand-edited config must be dropped, not seeded");
        assertTrue(
                records.stream()
                        .anyMatch(rec -> rec.getLevel() == Level.WARNING
                                && String.valueOf(rec.getMessage()).contains("Dropping the SSH authorized key")),
                "dropping the bad key must be logged at WARNING");
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
        ExecutorService scrubs = Executors.newSingleThreadExecutor();
        XcpngComputerListener listener = new XcpngComputerListener();
        listener.setScrubExecutor(scrubs);
        try {
            listener.onOnline(computer, hudson.model.TaskListener.NULL);

            scrubs.shutdown();
            assertTrue(scrubs.awaitTermination(30, TimeUnit.SECONDS), "the scrub should finish");
            assertTrue(
                    fake.calls().contains("clearGuestSecret:" + agent.getVmRef()),
                    "coming online must scrub the seed secret for the agent's VM, calls were " + fake.calls());
        } finally {
            scrubs.shutdownNow();
        }
    }

    @Test
    void onlineHandsTheScrubOffTheConnectingThread(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);
        XcpngAgent agent =
                (XcpngAgent) cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-2", activityId("xcpng-agent-2"));
        r.jenkins.addNode(agent);
        Computer computer = agent.toComputer();

        // A single worker, occupied by a task that will not return until the gate opens: whatever onOnline
        // hands over sits in the queue behind it. That models the case the change exists for -- a pool slow
        // or unreachable exactly while clones connect -- without needing a real timeout to elapse. A scrub
        // done inline would reach the client here regardless of the gate, so the pre-gate assertion below is
        // what separates the two.
        ExecutorService scrubs = Executors.newSingleThreadExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        scrubs.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        XcpngComputerListener listener = new XcpngComputerListener();
        listener.setScrubExecutor(scrubs);
        try {
            listener.onOnline(computer, hudson.model.TaskListener.NULL);

            assertTrue(
                    fake.calls().stream().noneMatch(call -> call.startsWith("clearGuestSecret:")),
                    "onOnline must return with the scrub still queued, not run it on the connecting thread, "
                            + "calls were " + fake.calls());

            gate.countDown();
            scrubs.shutdown();
            assertTrue(scrubs.awaitTermination(30, TimeUnit.SECONDS), "the queued scrub should finish");
            assertTrue(
                    fake.calls().contains("clearGuestSecret:" + agent.getVmRef()),
                    "the deferred scrub must still reach the client once a worker is free, calls were " + fake.calls());
        } finally {
            gate.countDown();
            scrubs.shutdownNow();
        }
    }

    @Test
    void onlineSurvivesAnExecutorThatRejectsTheScrub(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);
        XcpngAgent agent =
                (XcpngAgent) cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-3", activityId("xcpng-agent-3"));
        r.jenkins.addNode(agent);
        Computer computer = agent.toComputer();

        // An already-shut-down executor is what the remoting pool becomes while Jenkins is going down, and
        // it is the only state a cached pool rejects from. An agent connecting at that moment must still
        // come online: this listener promises to log its failures rather than throw them, and handing the
        // work off is no exception to that.
        ExecutorService scrubs = Executors.newSingleThreadExecutor();
        scrubs.shutdown();
        assertTrue(scrubs.awaitTermination(30, TimeUnit.SECONDS), "the empty executor should shut down at once");
        XcpngComputerListener listener = new XcpngComputerListener();
        listener.setScrubExecutor(scrubs);

        assertDoesNotThrow(
                () -> listener.onOnline(computer, hudson.model.TaskListener.NULL),
                "a rejected scrub must not escape into the connection sequence");
        assertTrue(
                fake.calls().stream().noneMatch(call -> call.startsWith("clearGuestSecret:")),
                "a rejected scrub cannot have reached the client, calls were " + fake.calls());
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

    // ---- The async launch() path: timeout, cancellation mid-boot, failure through launch ----

    /** A cloud backed by the given fake that keeps the production online wait (unlike {@link #cloudBackedBy}). */
    private static XcpngCloud onlineWaitingCloudBackedBy(FakeHypervisorClient fake, int maxInstances) {
        XcpngCloud cloud = new XcpngCloud(
                "xcpng", "https://pool.example.test", "cred", false, maxInstances, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        return cloud;
    }

    @Test
    void aProvisionThatNeverComesOnlineTimesOutAndDestroysTheVm(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = onlineWaitingCloudBackedBy(fake, 2);
        // The fake agent never connects, so keep the production online wait but shrink it: the task gives
        // up in ~100ms instead of five minutes and takes the timeout-and-teardown path the production
        // constants would make untestable.
        cloud.setOnlineWait(100L, 10L);
        r.jenkins.clouds.add(cloud);

        NodeProvisioner.PlannedNode planned = cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 1)
                .iterator()
                .next();

        // A clone that boots but never connects must not keep its VM: the timeout has to tear it down, or
        // the VM (and its disks) leak on the pool for the controller's whole lifetime.
        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> planned.future.get(30, TimeUnit.SECONDS));
        assertInstanceOf(
                IllegalStateException.class,
                thrown.getCause(),
                "the online timeout should surface as an IllegalStateException: " + thrown.getCause());
        assertTrue(
                fake.calls().stream().anyMatch(c -> c.startsWith("destroyWithDisks:")),
                "an agent that never comes online must have its VM destroyed on timeout: " + fake.calls());
    }

    @Test
    void aPlannedNodeCancelledAfterRegistrationTearsDownTheOrphan(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = onlineWaitingCloudBackedBy(fake, 2);
        // Keep the online wait so the task is still looping in awaitOnline when the cancel lands; a generous
        // timeout so the test never races the deadline, a small poll so the cancel is noticed promptly. This
        // reaches the "completed after cancellation" branch that the existing cancel-before-start test cannot.
        cloud.setOnlineWait(TimeUnit.MINUTES.toMillis(5), 10L);
        // A controllable executor so the orphan teardown can be awaited: fake.calls() is a live view, not a
        // snapshot, so the call log must not be read while the worker thread is still appending to it.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        cloud.setProvisionExecutor(exec);
        r.jenkins.clouds.add(cloud);
        try {
            NodeProvisioner.PlannedNode planned = cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 1)
                    .iterator()
                    .next();

            // Cancel only once the task has registered the node, so the cancel lands after registration rather
            // than before the task starts (which the existing test covers and which never reaches this branch).
            long deadline = System.currentTimeMillis() + 30_000;
            while (r.jenkins.getNodes().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(r.jenkins.getNodes().isEmpty(), "the provision should have registered its node");
            XcpngAgent orphan =
                    assertInstanceOf(XcpngAgent.class, r.jenkins.getNodes().get(0));
            String vmRef = orphan.getVmRef();

            planned.future.cancel(false);

            // awaitOnline returns on the cancel, complete(node) then returns false, and the task tears the
            // orphan down before it finishes; await that completion so the call log is settled before reading.
            exec.shutdown();
            assertTrue(exec.awaitTermination(30, TimeUnit.SECONDS), "the cancelled provision's task should finish");

            // A cancelled-mid-boot VM must not be left running on the pool.
            assertTrue(
                    fake.calls().contains("destroyWithDisks:" + vmRef),
                    "a node cancelled after registration must have its orphaned VM destroyed: " + fake.calls());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void aProvisionThatFailsThroughLaunchReleasesItsReservation(JenkinsRule r) throws Exception {
        // failStart() makes the clone succeed and the start throw, so the failure surfaces through the async
        // launch() task rather than the direct provisionNode() call the existing failure test uses.
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").failStart();
        XcpngCloud cloud = onlineWaitingCloudBackedBy(fake, 2);
        // The start throws before any online wait, so the wait setting is irrelevant here; a real single-thread
        // executor runs the task so provision() returns before it completes and the future is awaited below.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        cloud.setProvisionExecutor(exec);
        r.jenkins.clouds.add(cloud);
        try {
            NodeProvisioner.PlannedNode planned = cloud.provision(new Cloud.CloudState(Label.get("xcpng-linux"), 0), 1)
                    .iterator()
                    .next();

            ExecutionException thrown =
                    assertThrows(ExecutionException.class, () -> planned.future.get(30, TimeUnit.SECONDS));
            assertInstanceOf(
                    HypervisorException.class,
                    thrown.getCause(),
                    "the start failure should surface as the client's own exception: " + thrown.getCause());
            assertTrue(
                    fake.calls().stream().anyMatch(c -> c.startsWith("destroyWithDisks:")),
                    "a clone that fails after start must be destroyed, not leaked: " + fake.calls());

            // The whenComplete decrement runs off the completing thread, so spin briefly for it. If it broke on
            // the exceptional path, every failed provision would permanently shrink availableCapacity() and
            // eventually wedge the cloud at zero capacity with no VMs running.
            long deadline = System.currentTimeMillis() + 30_000;
            while (cloud.inFlightCount() != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(
                    0, cloud.inFlightCount(), "a failed provision must release its reservation, not wedge the cap");
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
        try {
            new XcpngWarmPoolMaintainer().execute(TaskListener.NULL);

            launches.shutdown();
            assertTrue(launches.awaitTermination(30, TimeUnit.SECONDS), "the healthy cloud's launch should finish");
            assertEquals(
                    1, warmNodeCount(r), "the healthy cloud's spare must launch despite the broken cloud throwing");
        } finally {
            launches.shutdownNow();
        }
    }

    // ---- Warm pool: cloud-stats visibility (#56) ----

    /** The one warm spare on the pool, failing the assertion if the reconcile produced anything else. */
    private static XcpngAgent theOnlySpare(JenkinsRule r) {
        assertEquals(1, r.jenkins.getNodes().size(), "expected exactly one spare on the pool");
        return assertInstanceOf(XcpngAgent.class, r.jenkins.getNodes().get(0));
    }

    @Test
    void warmSpareIsRegisteredWithCloudStats(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = warmCloudBackedBy(fake, 3, 1);
        r.jenkins.clouds.add(cloud);

        reconcileAndSettle(cloud);
        XcpngAgent spare = theOnlySpare(r);

        // The bug: a warm spare bypasses the NodeProvisioner, so no ProvisioningActivity is ever filed, and
        // getActivityFor throws IllegalStateException on this TrackedItem. onStarted in launch() fixes it.
        ProvisioningActivity activity = CloudStatistics.get().getActivityFor(spare);
        assertNotNull(activity, "the warm spare must have a cloud-stats activity");
        assertSame(spare.getId(), activity.getId(), "the activity must key off the agent's own id, not a rebuilt one");

        // Once the activity is filed, cloud-stats' own OperationListener advances it to LAUNCHING as the
        // spare's computer starts up. That listener can only find the activity because onStarted filed it, so
        // reaching LAUNCHING also proves the spare is genuinely tracked, not filed once and then orphaned.
        assertEquals(
                ProvisioningActivity.Phase.LAUNCHING,
                activity.getCurrentPhase(),
                "a tracked spare should progress into the LAUNCHING phase under cloud-stats");
    }

    @Test
    void deletingAWarmSpareDoesNotThrowFromCloudStats(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = warmCloudBackedBy(fake, 3, 1);
        r.jenkins.clouds.add(cloud);

        reconcileAndSettle(cloud);
        XcpngAgent spare = theOnlySpare(r);
        ProvisioningActivity activity = CloudStatistics.get().getActivityFor(spare);

        // The other half of #56: cloud-stats' node-deletion listener also calls getActivityFor, so removing an
        // untracked spare threw IllegalStateException out of removeNode. With the activity filed, the delete
        // closes it cleanly instead.
        assertDoesNotThrow(
                () -> r.jenkins.removeNode(spare), "removing a tracked spare must not throw from cloud-stats");
        assertEquals(
                ProvisioningActivity.Status.OK,
                activity.getStatus(),
                "a spare removed before use is a clean completion, not a failure");
        assertEquals(
                ProvisioningActivity.Phase.COMPLETED,
                activity.getCurrentPhase(),
                "deleting the spare should close its activity");
    }

    @Test
    void aFailedWarmLaunchIsRecordedAsFailedInCloudStats(JenkinsRule r) throws Exception {
        // failStart() clones the spare and then throws from start, so the launch fails after the activity is
        // opened but before any computer exists. No computer means cloud-stats' own launch/deletion listeners
        // never fire, so without the explicit onFailure the activity would hang in PROVISIONING forever.
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").failStart();
        XcpngCloud cloud = warmCloudBackedBy(fake, 3, 1);
        r.jenkins.clouds.add(cloud);

        reconcileAndSettle(cloud);

        assertEquals(0, warmNodeCount(r), "a spare whose start throws must not be registered");
        assertTrue(
                fake.calls().stream().anyMatch(c -> c.startsWith("destroyWithDisks:")),
                "the clone that failed to start must be destroyed, not leaked: " + fake.calls());

        // Exactly one activity, for this template, closed as a failure. Drop the onFailure and cloud-stats
        // never learns the launch threw: the abandoned activity is left looking successful (OK), never FAIL.
        List<ProvisioningActivity> activities = CloudStatistics.get().getActivities();
        assertEquals(1, activities.size(), "the failed launch should still leave one tracked activity");
        ProvisioningActivity activity = activities.get(0);
        assertEquals("jenkins-golden-debian", activity.getId().getTemplateName());
        assertEquals(
                ProvisioningActivity.Status.FAIL,
                activity.getStatus(),
                "a warm launch that threw must be recorded as a failed activity");
        // The failure path must still release its reservation: recording the failure in cloud-stats runs
        // before the future completes, so a stats call that escaped would strand this counter and wedge the
        // cap. (The onFailure call is guarded against exactly that; this locks the invariant it protects.)
        assertEquals(0, cloud.inFlightCount(), "a failed warm launch must release its reservation, not wedge the cap");
    }

    // ---- Leaked-VM recording and sweep (#27) ----

    @Test
    void sweepReclaimsARecordedLeakedVm(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);
        cloud.recordLeakedVm("vm/leaked/1");

        cloud.sweepLeakedVms();

        assertTrue(
                fake.calls().contains("destroyWithDisks:vm/leaked/1"),
                "the sweep must reissue the destroy for a recorded leak: " + fake.calls());
        assertTrue(
                cloud.leakedVmRefs().isEmpty(), "a reclaimed VM must be dropped from the set: " + cloud.leakedVmRefs());
    }

    @Test
    void sweepKeepsARefItStillCannotDestroy(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").failDestroy();
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);
        cloud.recordLeakedVm("vm/leaked/1");

        cloud.sweepLeakedVms();

        assertTrue(
                fake.calls().contains("destroyWithDisks:vm/leaked/1"),
                "the sweep must attempt the destroy: " + fake.calls());
        assertTrue(
                cloud.leakedVmRefs().contains("vm/leaked/1"),
                "a ref that still cannot be destroyed must stay recorded for the next tick: " + cloud.leakedVmRefs());
    }

    @Test
    void sweepWithNothingRecordedOpensNoClient(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        cloud.sweepLeakedVms();

        assertTrue(
                fake.calls().isEmpty(), "a sweep with nothing recorded must not even open a client: " + fake.calls());
    }

    @Test
    void aFailedTeardownIsRecordedThenReclaimedOnTheNextSweep(JenkinsRule r) throws Exception {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian").failDestroy();
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);
        XcpngAgent agent =
                (XcpngAgent) cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1"));
        r.jenkins.addNode(agent);

        // The teardown's destroy fails: the node is still removed, but the VM ref is recorded rather than lost.
        agent.terminate();
        assertFalse(r.jenkins.getNodes().contains(agent), "the node is removed even though its destroy failed");
        assertTrue(
                cloud.leakedVmRefs().contains(agent.getVmRef()),
                "the failed teardown must record the VM as leaked: " + cloud.leakedVmRefs());

        // The pool recovers; the next sweep reissues the destroy and the ref clears.
        fake.recoverDestroy();
        cloud.sweepLeakedVms();

        assertTrue(cloud.leakedVmRefs().isEmpty(), "the recovered sweep must reclaim the VM: " + cloud.leakedVmRefs());
    }

    @Test
    void aFailedProvisionCleanupIsRecordedThenReclaimedOnTheNextSweep(JenkinsRule r) {
        // Both failures at once, which is the realistic pairing: the pool goes unreachable, so the start throws
        // and the cleanup destroy that follows throws on the same blip. The clone exists on the pool and no
        // agent was ever built around it, so this set is the only thing that can still name it.
        FakeHypervisorClient fake =
                new FakeHypervisorClient("jenkins-golden-debian").failStart().failDestroy();
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);

        assertThrows(
                HypervisorException.class,
                () -> cloud.provisionNode(LINUX_TEMPLATE, "xcpng-agent-1", activityId("xcpng-agent-1")));
        assertTrue(
                cloud.leakedVmRefs().contains("vm/xcpng-agent-1/1"),
                "a cleanup destroy that failed must record the clone as leaked: " + cloud.leakedVmRefs());

        // The pool recovers; the sweep reclaims it with no operator and no external reaper involved.
        fake.recoverDestroy();
        cloud.sweepLeakedVms();

        assertTrue(
                cloud.leakedVmRefs().isEmpty(), "the recovered sweep must reclaim the clone: " + cloud.leakedVmRefs());
    }

    @Test
    void theMaintainerSweepsLeakedVms(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(fake, 2);
        r.jenkins.clouds.add(cloud);
        cloud.recordLeakedVm("vm/leaked/1");

        // The maintainer tick must run the sweep, not only reconcile the warm pool: that is what turns a
        // recorded leak into an actual destroy without an operator or the external reaper stepping in.
        new XcpngWarmPoolMaintainer().execute(TaskListener.NULL);

        assertTrue(
                fake.calls().contains("destroyWithDisks:vm/leaked/1"),
                "the maintainer tick must sweep recorded leaks: " + fake.calls());
        assertTrue(cloud.leakedVmRefs().isEmpty(), "the swept VM must be dropped: " + cloud.leakedVmRefs());
    }

    @Test
    void recordedLeakedVmsSurviveAConfigRoundTrip(JenkinsRule r) {
        XcpngCloud cloud = cloudBackedBy(new FakeHypervisorClient("jenkins-golden-debian"), 2);
        r.jenkins.clouds.add(cloud);
        cloud.recordLeakedVm("vm/leaked/1");

        // Recording rather than retrying inline earns its keep only by surviving a controller restart, which is
        // an XStream round-trip of config.xml. A transient set would come back empty and the VM would leak
        // undetected past the very restart the durable set exists to bridge.
        XcpngCloud reloaded =
                (XcpngCloud) jenkins.model.Jenkins.XSTREAM2.fromXML(jenkins.model.Jenkins.XSTREAM2.toXML(cloud));
        assertTrue(
                reloaded.leakedVmRefs().contains("vm/leaked/1"),
                "a recorded leaked VM must survive a config reload: " + reloaded.leakedVmRefs());
    }
}
