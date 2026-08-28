package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The slots this controller has committed to agents Jenkins has not registered yet, held per cloud,
 * outside the cloud object.
 *
 * <p>This exists for the same reason {@link XcpngLeakedVmStore} does, and it is worth saying why they are
 * two classes rather than one. A cloud is rebuilt through its {@code @DataBoundConstructor} whenever
 * configuration is applied -- by configuration-as-code on reload, and by the ordinary web UI when an
 * operator saves the cloud at {@code /manage/cloud/<name>/configure}. Both hand back a <em>different</em>
 * object with every non-configuration field back at its initial value. Reservations lived on that object
 * as a transient map, and both capacity formulas subtract them, so a reload landing inside the
 * reservation window counted a committed slot as free and the next pass planned past
 * {@link XcpngCloud#getMaxInstances()} (#160).
 *
 * <p>What separates it from the leaked-VM store is lifetime, which is the whole reason it is not simply
 * another map on that class. A reference to a VM that failed to die must outlive a restart, so that store
 * is a {@link hudson.model.Saveable} with a file. A reservation must <em>not</em>: nothing is in flight
 * across a restart, and a commitment restored from disk would be a slot held for an agent that can never
 * arrive, wedged until its deadline. So this holds its state in memory only, has no file, and is
 * deliberately not {@code Saveable} -- which also keeps it out of the configuration-as-code export for
 * free, the property {@code XcpngCloudConfigurationAsCodeTest} pins for its sibling.
 *
 * <p>An {@link Extension} rather than a static map, so its lifetime is the controller's: a static would
 * carry reservations between the {@code JenkinsRule} instances of two tests, and the state this holds is
 * exactly the state a test asserts on.
 *
 * <p>Keyed by cloud name, which is what a rebuilt cloud carries across. A renamed or deleted cloud
 * therefore orphans its entries. Unlike the leaked-VM store that needs no backstop: an orphaned
 * reservation belongs to no live cloud, so no capacity formula reads it, and it is dropped by its own
 * deadline the next time anything prunes that key.
 *
 * <p>Holding the map here is not on its own enough to keep a cap, which is why this class also owns a lock
 * per cloud name (#162). A capacity pass reads the registered node list and the reservation count at two
 * different instants, and {@link XcpngCloud#release} takes the cloud's own monitor so that no release can
 * land between them. That argument covers one cloud object. During a reload two objects are live under one
 * name with <em>different</em> monitors, so the old object's in-flight launcher can release between the
 * replacement's two reads: the agent is in neither term -- not in the snapshot, because it had not
 * registered when the snapshot was taken, and not in the count, because its reservation has just gone --
 * and the replacement plans one agent past {@link XcpngCloud#getMaxInstances()}. The lock is keyed by name
 * rather than held on the cloud precisely because the name is the one thing the pair shares. See
 * {@link #computeUnderCapacityLock} for what may run inside it.
 */
@Extension
public class XcpngReservationStore {

    private static final Logger LOGGER = Logger.getLogger(XcpngReservationStore.class.getName());

    /**
     * Cloud name to that cloud's committed-but-unregistered slots, by node name.
     *
     * <p>Concurrent at both levels, and it has to be: during a reload two cloud objects share a name
     * briefly, and they do not share a monitor. {@link XcpngCloud#release} still holds the cloud's own
     * monitor, which is what keeps a release from landing between the two reads of a provisioning pass;
     * that argument holds within one cloud object, and the map is what keeps the structure consistent
     * across the pair.
     */
    private final ConcurrentMap<String, ConcurrentMap<String, Reservation>> byCloud = new ConcurrentHashMap<>();

    /**
     * Cloud name to the lock that serialises capacity decisions taken under that name.
     *
     * <p>One entry per name ever used, never removed. A renamed or deleted cloud leaves a bare lock behind,
     * which costs a few dozen bytes and is bounded by the number of distinct cloud names an operator has
     * configured; removing entries would race a thread that is about to take the lock it just found, for no
     * saving worth the reasoning.
     */
    private final ConcurrentMap<String, ReentrantLock> capacityLocks = new ConcurrentHashMap<>();

    /** The singleton. Never call this before extensions are augmented; every caller is a runtime path. */
    static XcpngReservationStore get() {
        return ExtensionList.lookupSingleton(XcpngReservationStore.class);
    }

    /**
     * Run {@code body} with every other capacity decision for this cloud name held off, and hand back what it
     * returns.
     *
     * <p>What belongs inside: the whole snapshot-count-reserve sequence of a provisioning or warm-pool pass,
     * and the release that hands a slot back. What must stay outside: anything that takes another lock. The
     * lock this takes is a leaf -- nothing held inside it acquires anything else -- and the cloud monitor is
     * the only lock ever held on the way in, so the order across the plugin is cloud monitor then this, and
     * there is no cycle to argue about. The warm-pool drain is left outside for that reason: it takes
     * {@link XcpngRetentionStrategy}'s monitor, and it decides a surplus rather than a cap, so it has no
     * reason to be in here.
     *
     * <p>Reentrant, because a pass already holding it calls back through {@link XcpngCloud#release} on the
     * warm-launch rejection path.
     *
     * <p>A cloud with no name has nothing to key a lock by and nothing to coordinate with -- {@link #reserve}
     * refuses to record anything for it -- so the body runs unguarded rather than serialising every nameless
     * cloud on one lock.
     */
    <T> T computeUnderCapacityLock(String cloudName, @NonNull Supplier<T> body) {
        if (cloudName == null) {
            return body.get();
        }
        ReentrantLock lock = capacityLocks.computeIfAbsent(cloudName, key -> new ReentrantLock());
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** {@link #computeUnderCapacityLock} for a body with nothing to return. */
    void runUnderCapacityLock(String cloudName, @NonNull Runnable body) {
        computeUnderCapacityLock(cloudName, () -> {
            body.run();
            return null;
        });
    }

    /** Commit a slot to an agent that does not exist as a node yet. */
    void reserve(
            String cloudName, @NonNull String nodeName, @NonNull String templateName, boolean warm, long expiresAt) {
        if (unkeyable(cloudName, "reserve a slot for " + nodeName)) {
            return;
        }
        byCloud.computeIfAbsent(cloudName, key -> new ConcurrentHashMap<>())
                .put(nodeName, new Reservation(templateName, warm, expiresAt));
    }

    /** Give a slot back: to the node it was taken for, or because the commitment will never become one. */
    void release(String cloudName, @NonNull String nodeName) {
        if (unkeyable(cloudName, "release the slot for " + nodeName)) {
            return;
        }
        ConcurrentMap<String, Reservation> forCloud = byCloud.get(cloudName);
        if (forCloud != null) {
            forCloud.remove(nodeName);
        }
    }

    /**
     * Drop the reservations that have done their job, or that never will, and name the second kind.
     *
     * <p>Returns the node names dropped for the deadline rather than logging them here, so the caller
     * reports them with the cloud and the deadline it configured. The first kind -- a node that turned up
     * registered -- is the normal case and says nothing.
     *
     * <p>An emptied cloud entry is left in place. There is no file to keep tidy, and removing it would race
     * a {@link #reserve} that has just built the map it is about to fill.
     */
    @NonNull
    List<String> prune(String cloudName, @NonNull Set<String> registered, long now) {
        List<String> expired = new ArrayList<>();
        if (cloudName == null) {
            return expired;
        }
        ConcurrentMap<String, Reservation> forCloud = byCloud.get(cloudName);
        if (forCloud == null) {
            return expired;
        }
        forCloud.entrySet().removeIf(entry -> {
            if (registered.contains(entry.getKey())) {
                return true;
            }
            if (entry.getValue().expiresAt() > now) {
                return false;
            }
            expired.add(entry.getKey());
            return true;
        });
        return expired;
    }

    /**
     * Whether this cloud still holds the slot it committed to one node.
     *
     * <p>By name rather than by count, so a caller can assert on the commitment it made without a
     * concurrent warm-pool tick's own reservations changing the answer.
     */
    boolean holds(String cloudName, @NonNull String nodeName) {
        Map<String, Reservation> forCloud = cloudName == null ? null : byCloud.get(cloudName);
        return forCloud != null && forCloud.containsKey(nodeName);
    }

    /** How many slots this cloud has committed and not yet delivered. */
    int count(String cloudName) {
        Map<String, Reservation> forCloud = cloudName == null ? null : byCloud.get(cloudName);
        return forCloud == null ? 0 : forCloud.size();
    }

    /** How many of those are for one template, whether or not they are warm spares. */
    int countForTemplate(String cloudName, @NonNull String templateName) {
        return count(cloudName, templateName, false);
    }

    /** How many of those are warm spares for one template, so repeated ticks do not stack provisions. */
    int countWarmForTemplate(String cloudName, @NonNull String templateName) {
        return count(cloudName, templateName, true);
    }

    private int count(String cloudName, @NonNull String templateName, boolean warmOnly) {
        Map<String, Reservation> forCloud = cloudName == null ? null : byCloud.get(cloudName);
        if (forCloud == null) {
            return 0;
        }
        int count = 0;
        for (Reservation reservation : forCloud.values()) {
            if ((!warmOnly || reservation.warm()) && reservation.templateName().equals(templateName)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether this cloud can be keyed at all. A hand-edited {@code config.xml} can arrive with no
     * {@code <name>}, and a cap that silently stopped counting would be worse than a noisy one.
     */
    private static boolean unkeyable(String cloudName, String what) {
        if (cloudName != null) {
            return false;
        }
        LOGGER.log(Level.WARNING, () -> "Cannot " + what + ": the cloud has no name to key its reservations by");
        return true;
    }

    /**
     * One committed-but-unregistered agent: which template it is for, whether it is a warm spare, and when
     * its cloud stops believing in it.
     */
    private record Reservation(String templateName, boolean warm, long expiresAt) {}
}
