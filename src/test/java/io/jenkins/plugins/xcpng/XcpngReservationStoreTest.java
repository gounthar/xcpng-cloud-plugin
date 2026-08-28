package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The capacity lock this store keys by cloud name (#162), covered on its own rather than through a cloud.
 *
 * <p>The bug it exists for is covered where it happens, in
 * {@link XcpngCloudConfigurationAsCodeTest#aReleaseCannotLandInsideACapacityPassHeldUnderTheSameName} and
 * its mirror: a reload leaves two cloud objects live under one name with different monitors, so the monitor
 * on either of them cannot serialise a capacity decision against the other. These three cover the
 * properties that argument rests on, each of which is a plausible way to get the lock wrong rather than a
 * restatement of what {@code ReentrantLock} does.
 *
 * <p>Everything here runs on an executor with a timeout. A lock bug of this shape blocks forever, and a
 * test that hangs tells a reader nothing and stalls CI until it is killed.
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
