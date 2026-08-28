package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The two things this store owns that a cloud cannot reach past: the capacity lock it keys by cloud name
 * (#162), and the deadline that ends a reservation nothing ever claimed (#159).
 *
 * <p>The lock tests come first, then the deadline ones. They share a file because they share a class, not
 * because they share an argument.
 *
 * <p>The lock's own bug is covered where it happens, in
 * {@link XcpngCloudConfigurationAsCodeTest#aReleaseCannotLandInsideACapacityPassHeldUnderTheSameName} and
 * its mirror: a reload leaves two cloud objects live under one name with different monitors, so the monitor
 * on either of them cannot serialise a capacity decision against the other. These three cover the
 * properties that argument rests on, each of which is a plausible way to get the lock wrong rather than a
 * restatement of what {@code ReentrantLock} does.
 *
 * <p>Every lock test runs on an executor with a timeout. A lock bug of this shape blocks forever, and a
 * test that hangs tells a reader nothing and stalls CI until it is killed. The deadline tests need none of
 * that: {@code prune} takes {@code now} as a parameter, so nothing about them waits.
 */
@WithJenkins
class XcpngReservationStoreTest {

    private static final long TIMEOUT_SECONDS = 30;

    /**
     * The lock must be reentrant, and this is load-bearing rather than defensive: {@code reconcileWarmPool}
     * holds it for the whole fill, and {@code launchWarmSpare}'s rejection path calls back through
     * {@code XcpngCloud.release}, which takes it again. A non-reentrant lock deadlocks that path against
     * itself, on a controller that is shutting down and has nobody watching.
     */
    @Test
    void aHolderCanTakeTheLockAgain(JenkinsRule r) throws Exception {
        XcpngReservationStore store = XcpngReservationStore.get();
        AtomicInteger depth = new AtomicInteger();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> nested = pool.submit(() -> store.runUnderCapacityLock(
                    "xcpng-lab", () -> store.runUnderCapacityLock("xcpng-lab", depth::incrementAndGet)));
            nested.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, depth.get(), "the inner body must have run, not merely failed to deadlock");
    }

    /**
     * One lock per cloud name, not one for the store. A single lock would be correct and would also make
     * every XCP-ng cloud on a controller queue behind whichever one is provisioning -- a cloud talking to a
     * pool that has gone away would stall the others for as long as its pass takes.
     */
    @Test
    void twoCloudNamesDoNotBlockEachOther(JenkinsRule r) throws Exception {
        XcpngReservationStore store = XcpngReservationStore.get();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Future<?> holder = pool.submit(() -> store.runUnderCapacityLock("xcpng-one", () -> {
                held.countDown();
                await(releaseHolder);
            }));
            assertTrue(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the holder must take its lock first");

            Future<?> other = pool.submit(() -> store.runUnderCapacityLock("xcpng-two", () -> {}));
            other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            releaseHolder.countDown();
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A cloud with no name has nothing to key a lock by, and {@code reserve} already refuses to record
     * anything for it. Serialising every nameless cloud on one shared lock would be the easy way to write
     * this and would couple clouds that have nothing to do with each other; running unguarded is the
     * deliberate choice, and this is what says so.
     */
    @Test
    void anUnnamedCloudIsNotSerialisedAgainstAnother(JenkinsRule r) throws Exception {
        XcpngReservationStore store = XcpngReservationStore.get();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Future<?> holder = pool.submit(() -> store.runUnderCapacityLock(null, () -> {
                held.countDown();
                await(releaseHolder);
            }));
            assertTrue(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the holder must enter its body first");

            Future<?> other = pool.submit(() -> store.runUnderCapacityLock(null, () -> {}));
            other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            releaseHolder.countDown();
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The guard the two tests above depend on: this harness really can tell exclusion from its absence. A
     * holder of one name blocks a second taker of that same name, so the fast completions asserted above are
     * the lock being per-name rather than the lock not working at all.
     */
    @Test
    void oneCloudNameExcludesASecondTakerOfIt(JenkinsRule r) throws Exception {
        XcpngReservationStore store = XcpngReservationStore.get();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Future<?> holder = pool.submit(() -> store.runUnderCapacityLock("xcpng-one", () -> {
                held.countDown();
                await(releaseHolder);
            }));
            assertTrue(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the holder must take its lock first");

            Future<?> second = pool.submit(() -> store.runUnderCapacityLock("xcpng-one", () -> {}));
            assertThrows(
                    TimeoutException.class,
                    () -> second.get(2, TimeUnit.SECONDS),
                    "a second taker of a held name must wait for it");

            releaseHolder.countDown();
            second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The deadline branch, which is the one thing standing between a plan core dropped and a slot wedged
     * for the life of the controller (#159).
     *
     * <p>Reachable here without a clock seam anywhere, because {@link XcpngReservationStore#prune} takes
     * {@code now} as a parameter and {@link XcpngReservationStore#reserve} takes the deadline. That is a
     * consequence of moving the map off the cloud in #161: the issue asked for a {@code LongSupplier} on
     * {@code XcpngCloud} matching {@code XcpngRetentionStrategy}'s, and after #161 there is nothing left
     * for it to do.
     *
     * <p>Both halves are asserted in one test on purpose. The expiry alone would pass against a
     * {@code prune} that dropped every unregistered reservation regardless of its deadline, which is the
     * mutation that breaks the cap rather than the backstop.
     */
    @Test
    void aReservationIsDroppedAtItsDeadlineAndNotBefore(JenkinsRule r) {
        XcpngReservationStore store = XcpngReservationStore.get();
        long now = 1_000_000L;
        store.reserve("xcpng-lab", "xcpng-dead", "jenkins-golden-debian", false, now - 1);
        store.reserve("xcpng-lab", "xcpng-live", "jenkins-golden-debian", false, now + 60_000);

        List<String> expired = store.prune("xcpng-lab", Set.of(), now);

        assertEquals(List.of("xcpng-dead"), expired, "only the reservation past its deadline may be reported");
        assertFalse(store.holds("xcpng-lab", "xcpng-dead"), "and it must be gone from the map");
        assertTrue(
                store.holds("xcpng-lab", "xcpng-live"),
                "a reservation still inside its window must survive: dropping it hands a slot back to an "
                        + "agent that is on its way, which is the failure the whole mechanism exists to prevent");
        assertEquals(1, store.count("xcpng-lab"), "and it must still count against the cap");
    }

    /**
     * A reservation whose deadline is exactly {@code now} is expired. The comparison is
     * {@code expiresAt > now}, so this pins which way the boundary falls rather than leaving the next
     * reader to work it out from the source; on a one-minute deadline either answer is defensible and only
     * one is what the code does.
     */
    @Test
    void aDeadlineFallingExactlyOnNowHasPassed(JenkinsRule r) {
        XcpngReservationStore store = XcpngReservationStore.get();
        long now = 2_000_000L;
        store.reserve("xcpng-lab", "xcpng-onthedot", "jenkins-golden-debian", false, now);

        assertEquals(List.of("xcpng-onthedot"), store.prune("xcpng-lab", Set.of(), now));
    }

    /**
     * A reservation the node turned up for is dropped without being reported, and that separation is the
     * point: the caller logs everything {@code prune} returns at INFO, because handing a slot back to a
     * plan that may still be live is worth a line in the log. The ordinary case happens on every
     * registration and must stay silent, or that line means nothing.
     */
    @Test
    void aRegisteredNodeIsDroppedWithoutBeingReportedAsExpired(JenkinsRule r) {
        XcpngReservationStore store = XcpngReservationStore.get();
        long now = 3_000_000L;
        store.reserve("xcpng-lab", "xcpng-arrived", "jenkins-golden-debian", false, now + 60_000);

        assertTrue(
                store.prune("xcpng-lab", Set.of("xcpng-arrived"), now).isEmpty(),
                "a node that registered inside its window is the normal case and says nothing");
        assertFalse(store.holds("xcpng-lab", "xcpng-arrived"), "its slot is now counted as an agent instead");
    }

    /** {@link CountDownLatch#await} without the checked exception, for a body that runs inside the lock. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the capacity lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
