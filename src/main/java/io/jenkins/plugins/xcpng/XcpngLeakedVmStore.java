package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.BulkChange;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * The VM references the plugin failed to destroy, held per cloud, outside the cloud object.
 *
 * <p>This exists because the set has to outlive the {@link XcpngCloud} instance that recorded it, and on
 * every configuration path but one it does not. A cloud is rebuilt through its
 * {@code @DataBoundConstructor} whenever configuration is applied: by configuration-as-code on reload
 * (#149), and by the ordinary web UI when an operator saves the cloud at {@code /manage/cloud/<name>/
 * configure}. Both hand back a <em>different</em> object with every non-configuration field back at its
 * initial value. A leaked-VM set living on that object is therefore forgotten by the act of editing
 * {@code maxInstances}, and the VM it was the last reference to leaks with nothing left to retry it.
 * Measured on 2026-08-26 against both paths; only an XStream reload of {@code config.xml} preserved it,
 * which is the one path the existing tests covered.
 *
 * <p>Round-tripping the set through the cloud's configuration would fix the symptom and be wrong: a
 * reference to a VM that failed to die is runtime state, not something an operator writes in
 * {@code casc.yaml}, and configuration-as-code's contract is that the YAML is the source of truth. So the
 * state moves out of the configuration object entirely and into its own file,
 * {@code $JENKINS_HOME/io.jenkins.plugins.xcpng.XcpngLeakedVmStore.xml}.
 *
 * <p>Deliberately a plain {@link Saveable} rather than a {@link jenkins.model.GlobalConfiguration}: the
 * latter would give us persistence for free and would also expose the set to configuration-as-code under
 * {@code unclassified}, which is the export this design exists to avoid.
 * {@code XcpngCloudConfigurationAsCodeTest} asserts that it stays out of the YAML.
 *
 * <p>Keyed by cloud name, which is what a rebuilt cloud carries across. A renamed cloud therefore
 * orphans its entries, and so does a deleted one; nothing sweeps those, since only a live cloud runs
 * {@link XcpngCloud#sweepLeakedVms}. That is the same backstop as before this class existed --
 * {@code tools/reaper.py} sweeps on the {@code xcpng-cloud} owner marker, which is not tied to a cloud
 * still being configured.
 */
@Extension
public class XcpngLeakedVmStore implements Saveable {

    private static final Logger LOGGER = Logger.getLogger(XcpngLeakedVmStore.class.getName());

    /**
     * Refs read out of a legacy {@code config.xml} by {@link XcpngCloud#readResolve()}, waiting for a store
     * to put them in.
     *
     * <p>Static, and holding data, because of when the two events happen. {@code readResolve} runs while
     * XStream is loading global configuration, which is too early to look an extension up, and by the time
     * anything would ask the store for that cloud's refs, configuration-as-code may already have replaced
     * the cloud object the legacy refs were read into. Migrating on demand would therefore lose exactly the
     * refs of an upgrading JCasC controller. Handing them off at deserialization time, before anything can
     * replace the object, is ordering-independent.
     *
     * <p>Entries are removed as they are consumed, so a deferral cannot be applied twice. The store drains
     * on construction as well as on every access, and {@link #drainAtStartup()} forces that construction
     * once configuration has loaded -- otherwise the refs would sit here unpersisted until the first record
     * or sweep, and a cloud saved before then would have already dropped the legacy field from
     * {@code config.xml}, losing them on the next restart.
     */
    private static final ConcurrentMap<String, Set<String>> PENDING_MIGRATIONS = new ConcurrentHashMap<>();

    /**
     * Guards the file write in {@link #save()}. Static rather than per-instance: the file path is fixed per
     * controller, so two store instances (a test building one directly, say) would otherwise race on it.
     */
    private static final Object SAVE_LOCK = new Object();

    /**
     * Cloud name to the VM refs that cloud could not destroy. Not final and not typed to the concurrent
     * implementations: {@link XmlFile#unmarshal} writes the deserialized collections straight into the
     * field, so whatever XStream built lands here and {@link #normalize()} swaps it for the concurrent
     * shapes the runtime paths need.
     */
    private Map<String, Set<String>> refsByCloud = new ConcurrentHashMap<>();

    public XcpngLeakedVmStore() {
        load();
        // Not only in load(): a store whose file does not exist yet still has to pick up a deferral.
        drainPendingMigrations();
    }

    /**
     * Build the store once global configuration has loaded, so a pre-#149 migration reaches disk promptly.
     *
     * <p>Without this the extension is not constructed until something first records or sweeps, and until
     * then the migrated refs live only in {@link #PENDING_MIGRATIONS}. An operator who saved the cloud in
     * that window would write a {@code config.xml} with the legacy field gone while the store file did not
     * yet hold it, and a restart there loses the refs -- which is the failure this whole class exists to
     * prevent, reintroduced during the upgrade to it.
     *
     * <p>{@code JOB_LOADED} is after both paths that produce a deferral: XStream deserialization of
     * {@code config.xml}, and configuration-as-code, which applies at {@code EXTENSIONS_AUGMENTED}. A
     * deferral is keyed by cloud name, so it does not matter which of the two objects reached it.
     */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void drainAtStartup() {
        // Drain explicitly rather than relying on this being the call that constructs the store. Extension
        // instances are created lazily on first ExtensionList access, so anything touching the store earlier
        // in startup would leave this a no-op and the deferral unpersisted -- a fragile ordering dependency
        // for a call whose whole job is to remove one.
        get().drainPendingMigrations();
    }

    /** The singleton. Never call this before extensions are augmented; every caller here is a runtime path. */
    static XcpngLeakedVmStore get() {
        return ExtensionList.lookupSingleton(XcpngLeakedVmStore.class);
    }

    /**
     * Hand refs read out of a pre-#149 {@code config.xml} to whatever store comes along. Touches no Jenkins
     * API, so it is safe to call from {@code readResolve} during configuration load.
     */
    static void deferMigration(String cloudName, Collection<String> vmRefs) {
        // A hand-edited config.xml can arrive with no <name>, and this runs while global configuration is
        // loading: throwing here would fail the whole load over an orphaned ref nothing could key anyway.
        if (cloudName == null || vmRefs == null || vmRefs.isEmpty()) {
            return;
        }
        // compute(), for the fourth and last time in this class: computeIfAbsent releases the per-key lock
        // before addAll runs, and drainPendingMigrations removes this very key. A drain landing in that gap
        // takes the set away and the addAll then updates a detached copy, so the ref reaches neither the
        // store nor the file. remove() takes the same lock compute() holds, so whichever wins, nothing is
        // lost: the drain either sees the refs or leaves a fresh entry for the next pass.
        PENDING_MIGRATIONS.compute(cloudName, (key, pending) -> {
            Set<String> target = pending == null ? new CopyOnWriteArraySet<>() : pending;
            target.addAll(vmRefs);
            return target;
        });
    }

    /**
     * Record a VM a teardown could not destroy. Returns whether it was new, so a duplicate record neither
     * writes the file nor logs again. Persisted at once: the VM outlives the controller process, so a leak
     * recorded before a restart must still be reclaimed after one.
     */
    boolean record(String cloudName, @NonNull String vmRef) {
        drainPendingMigrations();
        if (unkeyable(cloudName, "record leaked VM " + vmRef)) {
            return false;
        }
        // compute() rather than computeIfAbsent().add(): the add has to happen under the map's per-key lock,
        // the same one drop() takes, or the two interleave and lose the ref. computeIfAbsent returns the set
        // and releases the lock before the caller adds to it, which leaves exactly that window open.
        boolean[] added = {false};
        refsByCloud.compute(cloudName, (key, refs) -> {
            Set<String> target = refs == null ? new CopyOnWriteArraySet<>() : refs;
            added[0] = target.add(vmRef);
            return target;
        });
        if (added[0]) {
            saveQuietly();
        }
        return added[0];
    }

    /** A snapshot of one cloud's leaked refs. A copy: the caller iterates it while a sweep may be mutating. */
    @NonNull
    Set<String> refs(String cloudName) {
        drainPendingMigrations();
        if (unkeyable(cloudName, "read leaked VMs")) {
            return new LinkedHashSet<>();
        }
        Set<String> refs = refsByCloud.get(cloudName);
        return refs == null ? new LinkedHashSet<>() : new LinkedHashSet<>(refs);
    }

    /**
     * Drop the refs a sweep managed to destroy. The cloud's entry is removed once it empties, so a healthy
     * controller's file holds nothing rather than a row of empty sets.
     */
    void drop(String cloudName, @NonNull Collection<String> vmRefs) {
        drainPendingMigrations();
        if (vmRefs.isEmpty() || unkeyable(cloudName, "drop leaked VMs")) {
            return;
        }
        // The removal and the decision to delete the now-empty entry must be one atomic step, under the same
        // per-key lock record() takes. Read-then-remove loses a ref: a record() landing between the
        // isEmpty() check and the delete adds to the very set about to be dropped, and remove(key, value)
        // compares by equals against that same object, so it deletes the entry the new ref just went into.
        boolean[] changed = {false};
        refsByCloud.compute(cloudName, (key, refs) -> {
            if (refs == null) {
                return null;
            }
            changed[0] = refs.removeAll(vmRefs);
            return refs.isEmpty() ? null : refs;
        });
        if (changed[0]) {
            saveQuietly();
        }
    }

    /**
     * Whether this cloud cannot be keyed, because a hand-edited or truncated {@code config.xml} left it
     * without a {@code <name>}.
     *
     * <p>Returning rather than throwing, and the reason is not tidiness. {@code ConcurrentHashMap} rejects a
     * null key, so an unguarded call throws {@code NullPointerException} from inside
     * {@link XcpngCloud#recordLeakedVm}, which runs on the cleanup path of a failed provision. There the NPE
     * replaces the cleanup failure that was being handled and swallows its warning, so a nameless cloud
     * turns a logged teardown failure into a confusing one somewhere else. The ref is unkeyable either way;
     * the warning about the real failure is the thing worth keeping. {@link #deferMigration} already
     * refuses a null name for the same reason, and this brings the runtime paths in line with it.
     */
    private static boolean unkeyable(String cloudName, String what) {
        if (cloudName != null) {
            return false;
        }
        LOGGER.log(Level.WARNING, () -> "Could not " + what + ": the XCP-ng cloud has no name");
        return true;
    }

    /** Move anything {@link #deferMigration} left behind into this store, merging rather than replacing. */
    private void drainPendingMigrations() {
        if (PENDING_MIGRATIONS.isEmpty()) {
            return;
        }
        boolean migrated = false;
        for (String cloudName : PENDING_MIGRATIONS.keySet()) {
            Set<String> pending = PENDING_MIGRATIONS.remove(cloudName);
            if (pending == null || pending.isEmpty()) {
                continue;
            }
            // compute(), for the same reason record() uses it: computeIfAbsent().addAll() adds outside the
            // per-key lock, so a drop() landing in between sees an empty set, returns null, and the migrated
            // refs land in a set no longer in the map. Third site of the same shape; the other two were
            // fixed first and this one was left behind.
            boolean[] added = {false};
            refsByCloud.compute(cloudName, (key, refs) -> {
                Set<String> target = refs == null ? new CopyOnWriteArraySet<>() : refs;
                added[0] = target.addAll(pending);
                return target;
            });
            migrated |= added[0];
            LOGGER.log(
                    Level.INFO,
                    () -> "Migrated " + pending.size() + " leaked XCP-ng VM ref(s) for cloud " + cloudName
                            + " out of config.xml and into "
                            + configFile().getFile().getName());
        }
        if (migrated) {
            saveQuietly();
        }
    }

    /** Persist, logging rather than throwing: losing the file is bad, failing a teardown over it is worse. */
    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not persist the XCP-ng leaked-VM store");
        }
    }

    @Override
    public void save() throws IOException {
        if (BulkChange.contains(this)) {
            return;
        }
        XmlFile file = configFile();
        // Serialized, because XmlFile.write is not. Verified against jenkins-core 2.555.3 with javap: neither
        // write() nor this method carries the synchronized modifier, and each call builds its own
        // AtomicFileWriter and commits by rename. Two unsynchronized writers can therefore serialize different
        // snapshots and commit them in the opposite order, leaving the older one on disk -- so a ref recorded
        // by the losing thread is gone after a restart, which is the loss this class exists to prevent.
        // Taking the lock around the whole write, rather than only the rename, is what makes it correct: the
        // snapshot and its commit have to be one step, or the reordering just moves earlier.
        //
        // Reasoned, not demonstrated. No test here reddens when this lock is removed: a losing write needs one
        // thread's serialize-to-rename gap to span another's whole add-serialize-rename, and 8 writers against
        // a 400-ref document did not produce it. The lock stays because the ordering is genuinely unguaranteed
        // and three lines are cheaper than the loss, but treat it as an argument rather than as a caught bug.
        synchronized (SAVE_LOCK) {
            file.write(this);
        }
        // Outside the lock: a listener is arbitrary third-party code and must not run holding it.
        SaveableListener.fireOnChange(this, file);
    }

    /** Read the file back, if there is one. A missing file is a controller that has never leaked a VM. */
    private void load() {
        XmlFile file = configFile();
        if (!file.exists()) {
            return;
        }
        try {
            file.unmarshal(this);
        } catch (IOException e) {
            // Keep the empty store rather than failing extension initialisation: an unreadable file costs
            // the retry of whatever is recorded in it, while throwing here would cost the whole plugin.
            LOGGER.log(Level.WARNING, e, () -> "Could not read the XCP-ng leaked-VM store; starting empty");
        }
        normalize();
    }

    /** Swap whatever XStream deserialized for the concurrent shapes {@link #record} and {@link #drop} need. */
    private void normalize() {
        Map<String, Set<String>> deserialized = refsByCloud;
        ConcurrentMap<String, Set<String>> normalized = new ConcurrentHashMap<>();
        if (deserialized != null) {
            deserialized.forEach((cloudName, refs) -> {
                if (cloudName != null && refs != null && !refs.isEmpty()) {
                    normalized.put(cloudName, new CopyOnWriteArraySet<>(refs));
                }
            });
        }
        refsByCloud = normalized;
    }

    private static XmlFile configFile() {
        return new XmlFile(
                Jenkins.XSTREAM, new File(Jenkins.get().getRootDir(), XcpngLeakedVmStore.class.getName() + ".xml"));
    }
}
