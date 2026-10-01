package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.HypervisorException;
import io.jenkins.plugins.xcpng.client.ProvisionSpec;
import io.jenkins.plugins.xcpng.client.VmRef;
import io.jenkins.plugins.xcpng.client.VmState;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
    private final FakeSleeper time = new FakeSleeper();
    private final List<Duration> sleeps = time.sleeps;

    @AfterEach
    void clearInterrupt() {
        // A test that interrupts must not leave the flag set for the next one on this thread.
        Thread.interrupted();
    }

    private void guard(boolean connected) {
        XcpngAgent.refuseIfHaltedWhileConnected(fake, VM, connected, time, WAIT, POLL);
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

    /**
     * The wait is bounded by elapsed time, not by a count of reads. Against a pool that takes 30 s to answer
     * each read (the client's own read timeout), a count of twelve re-reads would hold the teardown thread for
     * about seven minutes. Here every read costs 30 s of fake time, so the guard must give up after the read
     * that crosses the minute, having made three reads rather than thirteen.
     */
    @Test
    void aSlowPoolDoesNotStretchTheWaitBeyondItsWindow() {
        VmState[] alwaysHalted = new VmState[20];
        Arrays.fill(alwaysHalted, VmState.HALTED);
        fake.scriptStates(VM.value(), alwaysHalted);
        Duration slowRead = Duration.ofSeconds(30);
        HypervisorClient slow = new SlowStateReads(fake, time, slowRead);

        assertThrows(
                HypervisorException.class,
                () -> XcpngAgent.refuseIfHaltedWhileConnected(slow, VM, true, time, WAIT, POLL));

        // The first read ends at 30 s and opens the window (deadline 90 s); re-reads end at 65 and 100 s.
        assertEquals(3, fake.stateReads(), "no read may start once the window has closed");
        assertTrue(
                time.nanoTime() <= slowRead.plus(WAIT).plus(POLL).plus(slowRead).toNanos(),
                "the guard may overrun its window by at most one poll and one read, not by minutes: "
                        + Duration.ofNanos(time.nanoTime()));
    }

    /** An interrupted wait refuses rather than deletes, and leaves the interrupt for the caller to see. */
    @Test
    void anInterruptedWaitRefusesAndKeepsTheInterrupt() {
        fake.scriptStates(VM.value(), VmState.HALTED);
        XcpngAgent.Sleeper interrupted = new XcpngAgent.Sleeper() {
            @Override
            public void sleep(Duration duration) throws InterruptedException {
                throw new InterruptedException("teardown cancelled");
            }

            @Override
            public long nanoTime() {
                return 0;
            }
        };

        assertThrows(
                HypervisorException.class,
                () -> XcpngAgent.refuseIfHaltedWhileConnected(fake, VM, true, interrupted, WAIT, POLL));

        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt must survive the refusal");
    }
    /** Delegates to the fake but charges every state read to the clock, as a pool slow to answer would. */
    private static final class SlowStateReads implements HypervisorClient {
        private final FakeHypervisorClient delegate;
        private final FakeSleeper time;
        private final Duration cost;

        SlowStateReads(FakeHypervisorClient delegate, FakeSleeper time, Duration cost) {
            this.delegate = delegate;
            this.time = time;
            this.cost = cost;
        }

        @Override
        public VmState state(VmRef vm) {
            VmState answer = delegate.state(vm);
            time.advance(cost);
            return answer;
        }

        @Override
        public VmRef resolveTemplate(String name) {
            return delegate.resolveTemplate(name);
        }

        @Override
        public VmRef cloneFromTemplate(VmRef template, ProvisionSpec spec) {
            return delegate.cloneFromTemplate(template, spec);
        }

        @Override
        public void start(VmRef vm) {
            delegate.start(vm);
        }

        @Override
        public void clearGuestSecret(VmRef vm) {
            delegate.clearGuestSecret(vm);
        }

        @Override
        public Optional<String> primaryIpAddress(VmRef vm) {
            return delegate.primaryIpAddress(vm);
        }

        @Override
        public void stop(VmRef vm) {
            delegate.stop(vm);
        }

        @Override
        public void destroyWithDisks(VmRef vm) {
            delegate.destroyWithDisks(vm);
        }

        @Override
        public void ping() {
            delegate.ping();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
