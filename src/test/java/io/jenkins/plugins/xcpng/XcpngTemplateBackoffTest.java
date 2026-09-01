package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The state machine behind #157: how long a template is left alone after a failed launch, and what resets
 * it.
 *
 * <p>Every test drives an injected clock rather than sleeping. The delays are twenty seconds and up, so a
 * test that waited them out would take longer than the rest of the suite put together and would still only
 * pin the boundary by luck.
 *
 * <p>What is deliberately not asserted here is that a delay equals some particular number of seconds.
 * {@link XcpngTemplateBackoff#BASE_DELAY_MILLIS} and {@link XcpngTemplateBackoff#MAX_DELAY_MILLIS} are a
 * tuning choice, and a test restating them would fail on every retune while proving nothing. The
 * properties below are the ones a reader would want to still hold after such a retune.
 */
@WithJenkins
class XcpngTemplateBackoffTest {

    private static final String CLOUD = "xcpng";
    private static final String TEMPLATE = "jenkins-golden-debian";

    /** A store on a clock the test moves, starting at an instant far from zero so nothing passes by accident. */
    private static XcpngTemplateBackoff onClock(AtomicLong clock) {
        clock.set(TimeUnit.DAYS.toMillis(1));
        XcpngTemplateBackoff backoff = XcpngTemplateBackoff.get();
        backoff.setClock(clock::get);
        return backoff;
    }

    /**
     * The default answer is yes. Worth pinning on its own: a store that opened closed would stop a healthy
     * cloud provisioning at all, and every other test here would still pass.
     */
    @Test
    void aTemplateThatHasNeverFailedIsReady(JenkinsRule r) {
        AtomicLong clock = new AtomicLong();
        XcpngTemplateBackoff backoff = onClock(clock);

        assertTrue(backoff.isReady(CLOUD, TEMPLATE));
        assertNull(backoff.backoffFor(CLOUD, TEMPLATE), "nothing should be held for a template that works");
    }

    /**
     * The first failures cost nothing, and the hold that follows them ends exactly at its deadline rather
     * than after it.
     *
     * <p>The free attempts are the half a first cut of this class got wrong. A cloud that has just torn
     * down a failed launch plans its replacement on the next round -- see
     * {@code XcpngProvisionTest#aFailedLaunchGivesItsReservationBackAtOnce}, which held that property long
     * before this class existed and went red when a backoff started from failure one. A pool that hiccups
     * once is not a broken template.
     *
     * <p>The boundary is the other half: {@code readyAt} is the first instant an attempt is allowed, so a
     * comparison written the other way round would hold a template for one extra provisioning round every
     * time. Same reasoning as the reservation deadline in
     * {@link XcpngReservationStoreTest#aDeadlineFallingExactlyOnNowHasPassed}.
     */
    @Test
    void theFirstFailuresAreFreeAndTheHoldAfterThemEndsAtItsDeadline(JenkinsRule r) {
        AtomicLong clock = new AtomicLong();
        XcpngTemplateBackoff backoff = onClock(clock);

        // The loop below proves nothing if it never runs, and a retune of FREE_FAILURES to 0 would do
        // exactly that while leaving this test green. Assert the denominator before trusting the zero.
        assertTrue(
                XcpngTemplateBackoff.FREE_FAILURES > 0,
                "this test only means something while there is a free allowance to spend");
        for (int failure = 1; failure <= XcpngTemplateBackoff.FREE_FAILURES; failure++) {
            assertNotNull(backoff.noteFailure(CLOUD, TEMPLATE));
            assertTrue(
                    backoff.isReady(CLOUD, TEMPLATE),
                    "failure " + failure + " is within the free allowance and must not hold the template");
        }

        XcpngTemplateBackoff.Backoff held = backoff.noteFailure(CLOUD, TEMPLATE);
        assertNotNull(held);
        assertEquals(XcpngTemplateBackoff.FREE_FAILURES + 1, held.consecutiveFailures());

        assertFalse(backoff.isReady(CLOUD, TEMPLATE), "the first failure past the allowance must hold it");
        clock.set(held.readyAt() - 1);
        assertFalse(backoff.isReady(CLOUD, TEMPLATE), "one millisecond short of the deadline is still held");
        clock.set(held.readyAt());
        assertTrue(backoff.isReady(CLOUD, TEMPLATE), "the deadline itself is the first instant allowed");
    }

    /**
     * Each further failure in a row waits longer than the one before, up to the cap and never past it.
     *
     * <p>The cap is what makes recovery free: with no gate to lift, an operator who has just fixed a
     * template waits at most one of these before the next attempt proves it. A schedule that kept doubling
     * would turn a typo into an outage measured in hours.
     */
    @Test
    void consecutiveFailuresWaitLongerEachTimeAndStopAtTheCap(JenkinsRule r) {
        AtomicLong clock = new AtomicLong();
        onClock(clock);

        assertTrue(XcpngTemplateBackoff.FREE_FAILURES > 0, "there must be a free allowance to check");
        for (int free = 1; free <= XcpngTemplateBackoff.FREE_FAILURES; free++) {
            assertEquals(0L, XcpngTemplateBackoff.delayAfter(free), "failure " + free + " should cost nothing");
        }

        long previous = 0L;
        boolean reachedTheCap = false;
        int increases = 0;
        for (int failures = XcpngTemplateBackoff.FREE_FAILURES + 1;
                failures <= XcpngTemplateBackoff.FREE_FAILURES + 12;
                failures++) {
            long delay = XcpngTemplateBackoff.delayAfter(failures);
            assertTrue(
                    delay <= XcpngTemplateBackoff.MAX_DELAY_MILLIS,
                    "failure " + failures + " waited " + delay + "ms, past the cap");
            if (delay == XcpngTemplateBackoff.MAX_DELAY_MILLIS) {
                reachedTheCap = true;
            } else {
                assertTrue(delay > previous, "failure " + failures + " did not wait longer than failure " + previous);
                increases++;
            }
            previous = delay;
        }
        assertTrue(reachedTheCap, "the schedule should reach its cap within a dozen failures");
        // Without this the else branch above is an unreachable escape hatch waiting to happen: lower
        // MAX_DELAY_MILLIS to the base and the very first delay is already the cap, so "waits longer each
        // time" is never asserted and this test passes having checked only the clamp. A branch that reads
        // as covering a case must be shown to have covered one.
        assertTrue(increases > 0, "the doubling was never exercised; only the cap was");
        assertEquals(
                XcpngTemplateBackoff.MAX_DELAY_MILLIS,
                XcpngTemplateBackoff.delayAfter(Integer.MAX_VALUE),
                "a template failing for days must clamp rather than overflow its doubling");
    }

    /**
     * A success resets the count, so the failure after it starts from the base again rather than resuming
     * where the last run of failures left off.
     *
     * <p>This is what "consecutive" means and it is the plausible way to get this class wrong: a counter
     * that only ever climbs would leave a cloud that has been up for a week holding every template at the
     * cap after a handful of unrelated transient failures, and no other test here would notice.
     */
    @Test
    void aSuccessfulLaunchResetsTheCountRatherThanPausingIt(JenkinsRule r) {
        AtomicLong clock = new AtomicLong();
        XcpngTemplateBackoff backoff = onClock(clock);

        XcpngTemplateBackoff.Backoff last = null;
        for (int failure = 1; failure <= XcpngTemplateBackoff.FREE_FAILURES + 1; failure++) {
            last = backoff.noteFailure(CLOUD, TEMPLATE);
        }
        assertNotNull(last);
        assertEquals(XcpngTemplateBackoff.FREE_FAILURES + 1, last.consecutiveFailures());
        assertFalse(backoff.isReady(CLOUD, TEMPLATE), "the run of failures should have earned a hold");

        backoff.noteSuccess(CLOUD, TEMPLATE);
        assertNull(backoff.backoffFor(CLOUD, TEMPLATE), "a success should leave nothing behind");
        assertTrue(backoff.isReady(CLOUD, TEMPLATE));

        assertEquals(
                1,
                backoff.noteFailure(CLOUD, TEMPLATE).consecutiveFailures(),
                "the next failure should start the schedule again, not resume it");
    }

    /**
     * One template's failures hold that template, not its neighbours, and not the same template name under
     * a different cloud.
     *
     * <p>Both halves are keying bugs a single-template, single-cloud test could never see. The second is
     * the one that matters in practice: two clouds pointed at two pools commonly name the same golden
     * image, and a broken pool must not stop the working one.
     */
    @Test
    void aHeldTemplateDoesNotHoldItsNeighboursOrItsNamesakeOnAnotherCloud(JenkinsRule r) {
        AtomicLong clock = new AtomicLong();
        XcpngTemplateBackoff backoff = onClock(clock);

        for (int failure = 1; failure <= XcpngTemplateBackoff.FREE_FAILURES + 1; failure++) {
            backoff.noteFailure(CLOUD, TEMPLATE);
        }

        assertFalse(backoff.isReady(CLOUD, TEMPLATE));
        assertTrue(backoff.isReady(CLOUD, "jenkins-agent-debian13"), "another template on the same cloud");
        assertTrue(backoff.isReady("xcpng-second-pool", TEMPLATE), "the same template name on another cloud");
    }

    /**
     * A cloud with no name -- reachable from a hand-edited {@code config.xml} -- records nothing and stays
     * ready, rather than throwing on the provisioning path.
     */
    @Test
    void anUnnamedCloudIsAlwaysReadyAndRecordsNothing(JenkinsRule r) {
        AtomicLong clock = new AtomicLong();
        XcpngTemplateBackoff backoff = onClock(clock);

        assertNull(backoff.noteFailure(null, TEMPLATE));
        assertTrue(backoff.isReady(null, TEMPLATE));
        assertNull(backoff.backoffFor(null, TEMPLATE));
    }
}
