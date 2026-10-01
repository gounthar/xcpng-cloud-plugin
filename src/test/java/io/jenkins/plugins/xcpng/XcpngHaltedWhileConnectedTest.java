package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorException;
import io.jenkins.plugins.xcpng.client.VmRef;
import io.jenkins.plugins.xcpng.client.VmState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The teardown guard for #48: a VM the pool reports {@code Halted} while its agent is still connected is not
 * destroyed until the pool agrees it is running, because Xen Orchestra's destroy skips the shutdown on exactly
 * that reading and removes the disks of a running domain.
 *
 * <p>No Jenkins here: the guard is a static function of a client, a VM, whether the channel is open and a
 * sleeper, so every branch is reachable directly. The end-to-end half, that {@code _terminate} calls it and
 * records the VM as leaked when it refuses, is in {@link XcpngRetentionStrategyTest}.
 */
class XcpngHaltedWhileConnectedTest {

    private static final VmRef VM = new VmRef("vm-1");
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(5);

    private final FakeHypervisorClient fake = new FakeHypervisorClient();
    private final List<Duration> sleeps = new ArrayList<>();
    private final XcpngAgent.Sleeper recorder = sleeps::add;

    @AfterEach
    void clearInterrupt() {
        // A test that interrupts must not leave the flag set for the next one on this thread.
        Thread.interrupted();
    }

    private void guard(boolean connected) {
        XcpngAgent.refuseIfHaltedWhileConnected(fake, VM, connected, recorder, WAIT, POLL);
    }

    /** No channel, no second opinion: the destroy goes ahead as it always did, without a single read. */
    @Test
    void anAgentThatIsNotConnectedIsNotChecked() {
        fake.scriptStates(VM.value(), VmState.HALTED);

        guard(false);

        assertEquals(0, fake.stateReads(), "an unconnected agent must cost no read at all");
        assertEquals(List.of(), sleeps);
    }

    /** The ordinary teardown: connected, running. One read, no wait. */
    @Test
    void aRunningVmUnderAConnectedAgentIsReleasedAtOnce() {
        fake.scriptStates(VM.value(), VmState.RUNNING);

        guard(true);

        assertEquals(1, fake.stateReads());
        assertEquals(List.of(), sleeps, "agreement must not cost a wait");
    }

    /** The case #48 recorded: the record lags, then catches up. The destroy is released once it does. */
    @Test
    void aStaleHaltedThatCorrectsItselfReleasesTheDestroy() {
        fake.scriptStates(VM.value(), VmState.HALTED, VmState.HALTED, VmState.RUNNING);

        guard(true);

        assertEquals(3, fake.stateReads());
        assertEquals(List.of(POLL, POLL), sleeps, "one wait per re-read, and none after the record agreed");
    }

    /** A disagreement that outlasts the wait refuses the destroy, so the caller can record the VM as leaked. */
    @Test
    void aHaltedThatNeverCorrectsRefusesTheDestroy() {
        VmState[] alwaysHalted = new VmState[20];
        Arrays.fill(alwaysHalted, VmState.HALTED);
        fake.scriptStates(VM.value(), alwaysHalted);

        HypervisorException refused = assertThrows(HypervisorException.class, () -> guard(true));

        assertEquals(13, fake.stateReads(), "the first read plus one per 5 s across 60 s");
        assertEquals(12, sleeps.size());
        assertTrue(refused.getMessage().contains("#48"), "the refusal must name why: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(VM.value()), refused.getMessage());
    }

    /**
     * A first read that fails gives no evidence either way, and failing the teardown on it would make an
     * ordinary destroy worse than before this guard existed. It falls through.
     */
    @Test
    void aFirstReadThatFailsLetsTheDestroyThrough() {
        fake.scriptStates(VM.value(), (VmState) null);

        guard(true);

        assertEquals(1, fake.stateReads());
        assertEquals(List.of(), sleeps);
    }

    /**
     * Once the contradiction has been seen, only a clean read that is not {@code Halted} releases the destroy.
     * A re-read that fails is not that, so a pool that stops answering mid-wait ends in a refusal, not a delete.
     */
    @Test
    void aReReadThatFailsDoesNotReleaseTheDestroy() {
        VmState[] answers = new VmState[13];
        answers[0] = VmState.HALTED; // the rest stay null: every re-read throws
        fake.scriptStates(VM.value(), answers);

        assertThrows(HypervisorException.class, () -> guard(true));

        assertEquals(13, fake.stateReads());
    }

    /** A failed re-read in the middle is skipped over, and a later clean Running still releases the destroy. */
    @Test
    void aFailedReReadFollowedByRunningReleasesTheDestroy() {
        fake.scriptStates(VM.value(), VmState.HALTED, null, VmState.RUNNING);

        guard(true);

        assertEquals(3, fake.stateReads());
        assertEquals(List.of(POLL, POLL), sleeps);
    }

    /** An interrupted wait refuses rather than deletes, and leaves the interrupt for the caller to see. */
    @Test
    void anInterruptedWaitRefusesAndKeepsTheInterrupt() {
        fake.scriptStates(VM.value(), VmState.HALTED);
        XcpngAgent.Sleeper interrupted = d -> {
            throw new InterruptedException("teardown cancelled");
        };

        assertThrows(
                HypervisorException.class,
                () -> XcpngAgent.refuseIfHaltedWhileConnected(fake, VM, true, interrupted, WAIT, POLL));

        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt must survive the refusal");
    }
}
