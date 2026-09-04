package io.jenkins.plugins.xcpng;

import hudson.Extension;
import hudson.ExtensionList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * How long a template whose launches keep failing is left alone before the next attempt, held per cloud,
 * outside the cloud object.
 *
 * <p>A template naming a golden image the pool does not have fails at {@code resolveTemplate}, before any
 * clone, so every individual attempt is correct: the node is torn down, the reservation comes back, and
 * nothing is left on the pool. It is the repetition that is the problem (#157). Measured on the lab on
 * 2026-08-28 with one queued build and a typo in the template name: fourteen attempts in about four
 * minutes, one every twenty seconds, each leaving a WARNING in the log and a FAIL in cloud-stats, and
 * nothing that would ever have stopped except the operator cancelling the build.
 *
 * <p>The first {@link #FREE_FAILURES} attempts are free, so a pool that hiccups once is retried at once
 * as before; past that the delay doubles from {@link #BASE_DELAY_MILLIS} up to {@link #MAX_DELAY_MILLIS},
 * so the same hour costs about a dozen attempts rather than a hundred and eighty. There is deliberately no
 * point at which it stops: recovery is the hard half of any gate -- a template can start resolving again because someone
 * fixed the pool, with nothing to tell the plugin -- and a cap on the delay makes recovery free instead,
 * because the attempt after the cap simply succeeds. The cap is what bounds how long an operator who has
 * just fixed a template waits to see it work.
 *
 * <p>Kept off the cloud for the reason {@link XcpngReservationStore} is: applying configuration rebuilds
 * the cloud through its {@code @DataBoundConstructor}, and every non-configuration field on the new object
 * starts empty. A backoff held there would reset on each reload, and during a reload two objects live
 * under one name would each keep their own count, halving the delay for as long as both are live. It is a
 * separate class from that store rather than another map on it because the two answer different questions
 * and share no locking: this one is read without the capacity lock and takes nothing itself.
 *
 * <p>In memory only, and deliberately not {@code Saveable}. A backoff restored from disk would hold a
 * template back for a failure that happened before a restart, which is exactly the state a restart should
 * clear; the first attempt after a restart is also the cheapest way to find out whether the pool was fixed
 * while the controller was down.
 *
 * <p>An {@link Extension} rather than a static map, so its lifetime is the controller's: a static would
 * carry one test's failures into the next {@code JenkinsRule}.
 */
@Extension
public class XcpngTemplateBackoff {

    /**
     * How many consecutive failures a template gets for free before anything is held back.
     *
     * <p>Not zero, and this is the half that took a broken test to notice. A cloud that has just torn down
     * a failed launch is expected to plan its replacement on the very next round -- {@code
     * XcpngProvisionTest#aFailedLaunchGivesItsReservationBackAtOnce} pins exactly that, and it is the
     * behaviour a pool that hiccuped once deserves. #157 is not about a launch that failed; it is about a
     * template that fails <em>every</em> launch, and two attempts is the cheapest evidence that tells the
     * two apart. A run of failures reaches the third attempt inside a minute either way.
     */
    static final int FREE_FAILURES = 2;

    /** How long a template is left alone once it has used up its {@link #FREE_FAILURES}. */
    static final long BASE_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(20);

    /**
     * The longest a template is ever left alone, however many times it has failed.
     *
     * <p>This is the recovery bound rather than a throttling choice: with no gate to lift, the wait an
     * operator serves after fixing a template is at most one of these. Five minutes keeps that tolerable
     * while still cutting a wedged template's attempts by more than an order of magnitude.
     */
    static final long MAX_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /**
     * A ceiling on the shift in {@link #delayAfter}, so a template failing for days cannot overflow the
     * doubling. Any value past five is already clamped by {@link #MAX_DELAY_MILLIS}; this only keeps the
     * arithmetic honest on the way there.
     */
    private static final int MAX_DOUBLINGS = 16;

    /**
     * Cloud name to that cloud's backed-off templates, by template name.
     *
     * <p>Concurrent at both levels for the same reason the reservation map is: during a reload two cloud
     * objects share a name and do not share a monitor, and both read and write this.
     *
     * <p>An entry exists only while a template is failing -- {@link #noteSuccess} removes it -- so a
     * healthy cloud holds nothing here, and the worst case is one entry per template ever configured.
     */
    private final ConcurrentMap<String, ConcurrentMap<String, Backoff>> byCloud = new ConcurrentHashMap<>();

    /**
     * Clock for the deadline math. Null in production, where {@link #now()} reads the system clock; a test
     * injects a fixed one so it can step over a delay without sleeping through it.
     */
    private volatile LongSupplier clock;

    /** The singleton. Never call this before extensions are augmented; every caller is a runtime path. */
    static XcpngTemplateBackoff get() {
        return ExtensionList.lookupSingleton(XcpngTemplateBackoff.class);
    }

    /**
     * Whether this template may be tried again now.
     *
     * <p>Read on the provisioning path and on the warm-pool fill, and deliberately <em>not</em> from
     * {@link XcpngCloud#canProvision}, which is the obvious place and the wrong one.
     * {@code Label.getClouds()} caches the set of clouds whose {@code canProvision} said yes, in a field it
     * only rebuilds when something trims the label cache, and {@code Label.isAssignable()} answers from
     * that cache once no node carries the label. Read off {@code jenkins-core-2.555.3}: a
     * {@code canProvision} that went false for the length of a backoff could therefore be remembered long
     * after the backoff expired, leaving a label that looks unassignable for a template that is working
     * again. Returning an empty collection from {@code provision} has the same effect on that round and is
     * cached by nothing.
     *
     * <p>A cloud or template with no name -- both reachable from a hand-edited {@code config.xml}, since
     * neither constructor rejects one -- has nothing to key by, so it is always ready and records nothing.
     * The alternative is worse than the missing backoff: {@code ConcurrentHashMap} throws on a null key, so
     * an unnamed template would take an NPE out of {@code provision} and into core's {@code NodeProvisioner}
     * on every round. Losing a backoff degrades to the behaviour this class exists to improve on; losing the
     * provisioning round does not.
     */
    boolean isReady(String cloudName, String templateName) {
        Backoff backoff = backoffFor(cloudName, templateName);
        return backoff == null || backoff.readyAt() <= now();
    }

    /**
     * Record that a launch for this template failed, and hand back the resulting state so the caller can
     * say so with its own cloud name in the message.
     *
     * <p>Returns null for a cloud with no name, which records nothing.
     */
    Backoff noteFailure(String cloudName, String templateName) {
        if (unkeyable(cloudName, templateName)) {
            return null;
        }
        long now = now();
        return byCloud.computeIfAbsent(cloudName, key -> new ConcurrentHashMap<>())
                .compute(templateName, (key, previous) -> {
                    int failures = previous == null ? 1 : previous.consecutiveFailures() + 1;
                    return new Backoff(failures, now + delayAfter(failures));
                });
    }

    /**
     * Record that a launch for this template worked, clearing whatever it had accumulated.
     *
     * <p>Called for a reconnect as well as a first launch, which is imprecise in one direction and harmless
     * for it: a reconnect proves the template resolved when its VM was cloned, not that it resolves now, so
     * a template deleted from the pool mid-build could have its backoff cleared by an agent reconnecting.
     * The next provision fails and re-enters the backoff one attempt later, which is a cheaper trade than
     * threading the distinction through the launcher.
     */
    void noteSuccess(String cloudName, String templateName) {
        ConcurrentMap<String, Backoff> forCloud = unkeyable(cloudName, templateName) ? null : byCloud.get(cloudName);
        if (forCloud != null) {
            forCloud.remove(templateName);
        }
    }

    /** How long to leave a template alone after this many consecutive failures. */
    static long delayAfter(int consecutiveFailures) {
        if (consecutiveFailures <= FREE_FAILURES) {
            return 0L;
        }
        int doublings = Math.min(consecutiveFailures - FREE_FAILURES - 1, MAX_DOUBLINGS);
        return Math.min(BASE_DELAY_MILLIS << doublings, MAX_DELAY_MILLIS);
    }

    /** This template's current backoff, or null if it is not in one. */
    Backoff backoffFor(String cloudName, String templateName) {
        ConcurrentMap<String, Backoff> forCloud = unkeyable(cloudName, templateName) ? null : byCloud.get(cloudName);
        return forCloud == null ? null : forCloud.get(templateName);
    }

    /** Whether this pair can be used as a map key at all. Either half missing means neither is usable. */
    private static boolean unkeyable(String cloudName, String templateName) {
        return cloudName == null || templateName == null;
    }

    /** The current instant in millis, from the injected clock in tests or the system clock in production. */
    private long now() {
        return clock != null ? clock.getAsLong() : System.currentTimeMillis();
    }

    /** Test seam: drive the deadline math off a fixed clock instead of the wall clock. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * One backed-off template: how many launches have failed in a row, and the instant the next attempt is
     * allowed.
     */
    record Backoff(int consecutiveFailures, long readyAt) {}
}
