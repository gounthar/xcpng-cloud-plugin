package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import io.jenkins.plugins.xcpng.client.VmState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The leaked-VM sweep and an entry the #48 guard refused to destroy (#306).
 *
 * <p>The guard refuses because the pool reads {@code Halted} while the agent is connected, and a delete on that
 * reading skips the shutdown and removes a running domain's disks. Such an entry is held: the sweep reads the
 * state first and sends the delete only on a reading that makes Xen Orchestra shut the VM down before
 * destroying it. Every other entry is swept exactly as before, which {@code XcpngProvisionTest} covers.
 */
@WithJenkins
class XcpngHeldLeakSweepTest {

    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);

    private static final String VM = "vm/held/1";

    private static XcpngCloud cloudBackedBy(JenkinsRule r, FakeHypervisorClient fake) {
        XcpngCloud cloud =
                new XcpngCloud("xcpng", "https://pool.example.test", "cred", null, 3, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> fake);
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static void recordHeld(XcpngCloud cloud, String vmRef) {
        cloud.recordLeakedVm(XcpngLeakedVm.of(vmRef, cloud).asHeld());
    }

    private static long destroyCount(FakeHypervisorClient fake) {
        return fake.calls().stream()
                .filter(c -> c.startsWith("destroyWithDisks:"))
                .count();
    }

    private static VmState[] repeated(VmState state, int times) {
        VmState[] answers = new VmState[times];
        Arrays.fill(answers, state);
        return answers;
    }

    /** The case #306 is about: the reading that refused the teardown must refuse the sweep too. */
    @Test
    void aHeldEntryThatStillReadsHaltedIsNotDeleted(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.scriptStates(VM, VmState.HALTED);
        recordHeld(cloud, VM);

        cloud.sweepLeakedVms();

        assertEquals(1, fake.stateReads(), "the sweep must have read the state, or this proves nothing");
        assertEquals(0, destroyCount(fake), "no delete on a Halted reading: " + fake.calls());
        assertTrue(cloud.leakedVmRefs().contains(VM), "the entry must stay for the next sweep");
    }

    /** UNKNOWN is not evidence the domain is down, so it holds as Halted does. */
    @Test
    void aHeldEntryWithAnUnmappedStateIsNotDeleted(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.scriptStates(VM, VmState.UNKNOWN);
        recordHeld(cloud, VM);

        cloud.sweepLeakedVms();

        assertEquals(1, fake.stateReads());
        assertEquals(0, destroyCount(fake), fake.calls().toString());
        assertTrue(cloud.leakedVmRefs().contains(VM));
    }

    /** A read that fails says nothing about the VM, so it holds too. */
    @Test
    void aHeldEntryWhoseStateCannotBeReadIsNotDeleted(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.scriptStates(VM, (VmState) null);
        recordHeld(cloud, VM);

        cloud.sweepLeakedVms();

        assertEquals(1, fake.stateReads());
        assertEquals(0, destroyCount(fake), fake.calls().toString());
        assertTrue(cloud.leakedVmRefs().contains(VM));
    }

    /**
     * Running is the reading on which Xen Orchestra shuts the VM down before removing its disks, so the delete
     * is safe and goes ahead. The control for the tests above: a sweep that never deleted a held entry would
     * pass all of them.
     */
    @Test
    void aHeldEntryThatReadsRunningIsDestroyedAndDropped(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.scriptStates(VM, VmState.RUNNING);
        recordHeld(cloud, VM);

        cloud.sweepLeakedVms();

        assertEquals(1, destroyCount(fake), fake.calls().toString());
        assertTrue(cloud.leakedVmRefs().isEmpty(), "a reclaimed entry is dropped: " + cloud.leakedVmRefs());
    }

    /** Paused and Suspended are not Halted either, so XO shuts them down first as well. */
    @Test
    void aHeldEntryThatReadsPausedOrSuspendedIsDestroyed(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.scriptStates("vm/held/paused", VmState.PAUSED);
        fake.scriptStates("vm/held/suspended", VmState.SUSPENDED);
        recordHeld(cloud, "vm/held/paused");
        recordHeld(cloud, "vm/held/suspended");

        cloud.sweepLeakedVms();

        assertEquals(2, destroyCount(fake), fake.calls().toString());
        assertTrue(cloud.leakedVmRefs().isEmpty(), cloud.leakedVmRefs().toString());
    }

    /** XO saying the VM no longer exists is the destroy's goal state: the entry goes, with no delete sent. */
    @Test
    void aHeldEntryWhoseVmIsGoneIsDroppedWithoutADelete(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.deleteOutOfBand(VM);
        recordHeld(cloud, VM);

        cloud.sweepLeakedVms();

        assertEquals(1, fake.stateReads(), "the sweep must have asked, or the drop came from somewhere else");
        assertEquals(0, destroyCount(fake), fake.calls().toString());
        assertTrue(cloud.leakedVmRefs().isEmpty(), "a gone VM's entry is done: " + cloud.leakedVmRefs());
    }

    /**
     * An unheld entry is swept as it always was: the delete goes out without a state read, whatever the pool
     * says. Holding every entry would stall the ordinary recovery from a pool that was briefly unreachable.
     */
    @Test
    void anUnheldEntryIsDeletedWithoutReadingItsState(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.scriptStates(VM, VmState.HALTED);
        cloud.recordLeakedVm(VM);

        cloud.sweepLeakedVms();

        assertEquals(0, fake.stateReads(), "an unheld entry costs no read");
        assertEquals(1, destroyCount(fake), fake.calls().toString());
        assertTrue(cloud.leakedVmRefs().isEmpty());
    }

    /**
     * A held entry that never clears is named once at SEVERE, at the tenth sweep, with the tool that can tell
     * from dom0 whether the domain is really running. Not every sweep, which would bury the log, and not the
     * first, which the teardown's own SEVERE already covers.
     */
    @Test
    void aStuckHeldEntryIsReportedOnceAtTheTenthSweep(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.scriptStates(VM, repeated(VmState.HALTED, 11));
        recordHeld(cloud, VM);
        List<LogRecord> severe = new ArrayList<>();
        Logger logger = Logger.getLogger(XcpngCloud.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE && record.getMessage().contains(VM)) {
                    severe.add(record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            for (int sweep = 1; sweep <= 9; sweep++) {
                cloud.sweepLeakedVms();
            }
            assertEquals(0, severe.size(), "nothing at SEVERE before the tenth sweep");
            cloud.sweepLeakedVms();
            assertEquals(1, severe.size(), "exactly one SEVERE at the tenth sweep");
            cloud.sweepLeakedVms();
            assertEquals(1, severe.size(), "and not again at the eleventh");
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(11, fake.stateReads(), "every sweep must have read the state");
        assertEquals(0, destroyCount(fake), fake.calls().toString());
        String message = severe.get(0).getMessage();
        assertTrue(message.contains("tools/reaper.py --cloud xcpng --dom0-check"), message);
        assertTrue(cloud.leakedVmRefs().contains(VM), "reporting it must not drop it: there is no age-out");
    }

    /** A held entry that clears after being stuck is still reclaimed, at whichever sweep the pool agrees. */
    @Test
    void aHeldEntryIsReclaimedOnceThePoolAgrees(JenkinsRule r) {
        FakeHypervisorClient fake = new FakeHypervisorClient("jenkins-golden-debian");
        XcpngCloud cloud = cloudBackedBy(r, fake);
        fake.scriptStates(VM, VmState.HALTED, VmState.HALTED, VmState.RUNNING);
        recordHeld(cloud, VM);

        cloud.sweepLeakedVms();
        cloud.sweepLeakedVms();
        assertEquals(0, destroyCount(fake), fake.calls().toString());
        cloud.sweepLeakedVms();

        assertEquals(1, destroyCount(fake), fake.calls().toString());
        assertFalse(cloud.leakedVmRefs().contains(VM));
    }
}
