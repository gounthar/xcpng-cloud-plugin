package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The leaked-VM set lives outside the cloud object (#149), so these cover the two things that move with it:
 * it must survive a controller restart on its own file, and a pre-#149 {@code config.xml} that carried the
 * set on the cloud must hand it over rather than lose it.
 *
 * <p>The paths that rebuild the cloud -- a configuration-as-code reload and an ordinary UI save -- are
 * covered where they live, in {@link XcpngCloudConfigurationAsCodeTest} and {@link XcpngCloudTest}.
 */
@WithJenkins
class XcpngLeakedVmStoreTest {

    private static final String STORE_FILE = XcpngLeakedVmStore.class.getName() + ".xml";

    /**
     * The whole point of recording a ref rather than retrying inline is that it bridges a restart, so the
     * store has to reach disk and read back. A restart is a fresh store instance over the same file, which is
     * exactly what the constructor does; asserting only through the live singleton would pass against a store
     * that never wrote anything.
     */
    @Test
    void aRecordedRefSurvivesARestart(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore.get().record("xcpng-lab", "OpaqueRef:leaked-1");

        File file = new File(Jenkins.get().getRootDir(), STORE_FILE);
        assertTrue(file.isFile(), "recording a leak must write the store's own file: " + file);
        assertTrue(
                Files.readString(file.toPath(), StandardCharsets.UTF_8).contains("OpaqueRef:leaked-1"),
                "the ref must actually be in the file, not only in memory");

        // A new instance over the same JENKINS_HOME is what a restarted controller builds.
        assertEquals(
                Set.of("OpaqueRef:leaked-1"),
                new XcpngLeakedVmStore().refs("xcpng-lab"),
                "a restarted controller must read its leaked refs back");
    }

    /** A swept ref must leave the file too, or a restart would resurrect a VM that is already destroyed. */
    @Test
    void aDroppedRefLeavesTheFile(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng-lab", "OpaqueRef:leaked-1");
        store.drop("xcpng-lab", List.of("OpaqueRef:leaked-1"));

        assertTrue(store.refs("xcpng-lab").isEmpty(), "the dropped ref must be gone from the live store");
        File file = new File(Jenkins.get().getRootDir(), STORE_FILE);
        assertTrue(file.isFile(), "the store must have written a file at all, or the assertion below is vacuous");
        String persisted = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse(
                persisted.contains("OpaqueRef:leaked-1"), "the dropped ref must be gone from the file: " + persisted);
    }

    /** Two clouds must not see each other's leaks; the key is the cloud name a rebuilt cloud carries across. */
    @Test
    void refsAreKeptPerCloud(JenkinsRule r) {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        store.record("xcpng-lab", "OpaqueRef:lab-1");
        store.record("xcpng-other", "OpaqueRef:other-1");

        assertEquals(Set.of("OpaqueRef:lab-1"), store.refs("xcpng-lab"));
        assertEquals(Set.of("OpaqueRef:other-1"), store.refs("xcpng-other"));
        assertTrue(store.refs("xcpng-unknown").isEmpty(), "a cloud that never leaked must read empty");
    }

    /**
     * A record landing while a sweep drops the last ref must not be swallowed.
     *
     * <p>Read-then-remove loses it: the sweep sees the set empty, a record adds to that same set, and
     * {@code remove(key, value)} compares by equals against the very object just added to, so it deletes the
     * entry the new ref went into. Both operations therefore run inside the map's per-key lock. Hammered
     * rather than choreographed -- the window is a few instructions wide, so this drives it repeatedly and
     * asserts the invariant that matters: a ref that record() reported as new is still readable afterwards.
     */
    @Test
    void aRecordConcurrentWithADropIsNotLost(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 300; i++) {
                String surviving = "OpaqueRef:new-" + i;
                String doomed = "OpaqueRef:old-" + i;
                store.record("xcpng-lab", doomed);

                CountDownLatch go = new CountDownLatch(1);
                Future<?> dropping = pool.submit(() -> {
                    awaitQuietly(go);
                    store.drop("xcpng-lab", List.of(doomed));
                });
                Future<?> recording = pool.submit(() -> {
                    awaitQuietly(go);
                    store.record("xcpng-lab", surviving);
                });
                go.countDown();
                dropping.get(30, TimeUnit.SECONDS);
                recording.get(30, TimeUnit.SECONDS);

                assertTrue(
                        store.refs("xcpng-lab").contains(surviving),
                        "a ref recorded while a sweep emptied the set must survive, iteration " + i);
                store.drop("xcpng-lab", List.of(surviving));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A deferred migration must reach disk without waiting for a record or a sweep.
     *
     * <p>Otherwise the refs live only in the static pending map: an operator saving the cloud in that window
     * writes a config.xml with the legacy field already gone, and a restart then has them in neither place.
     * {@code drainAtStartup} is what core calls at {@code JOB_LOADED}; calling it directly is the same entry
     * point without standing up a second controller.
     */
    @Test
    void aDeferredMigrationIsPersistedAtStartup(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore.deferMigration("xcpng-upgraded", List.of("OpaqueRef:legacy-1"));

        XcpngLeakedVmStore.drainAtStartup();

        File file = new File(Jenkins.get().getRootDir(), STORE_FILE);
        assertTrue(file.isFile(), "the startup drain must have written the store file: " + file);
        assertTrue(
                Files.readString(file.toPath(), StandardCharsets.UTF_8).contains("OpaqueRef:legacy-1"),
                "a migrated ref must be on disk before anything records or sweeps");
        assertEquals(
                Set.of("OpaqueRef:legacy-1"),
                new XcpngLeakedVmStore().refs("xcpng-upgraded"),
                "and must therefore survive the restart that follows");
    }

    /**
     * The drain is the third site of the same non-atomic shape as {@code record} and {@code drop}, and it
     * was the one left behind when those two were fixed. A sweep landing mid-drain must not strand the
     * migrated refs in a set that is no longer in the map.
     */
    @Test
    void aDrainConcurrentWithADropIsNotLost(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 200; i++) {
                String migrating = "OpaqueRef:migrated-" + i;
                String doomed = "OpaqueRef:swept-" + i;
                store.record("xcpng-lab", doomed);
                XcpngLeakedVmStore.deferMigration("xcpng-lab", List.of(migrating));

                CountDownLatch go = new CountDownLatch(1);
                Future<?> dropping = pool.submit(() -> {
                    awaitQuietly(go);
                    store.drop("xcpng-lab", List.of(doomed));
                });
                Future<?> draining = pool.submit(() -> {
                    awaitQuietly(go);
                    store.refs("xcpng-lab");
                });
                go.countDown();
                dropping.get(30, TimeUnit.SECONDS);
                draining.get(30, TimeUnit.SECONDS);

                assertTrue(
                        store.refs("xcpng-lab").contains(migrating),
                        "a migrated ref must survive a concurrent sweep, iteration " + i);
                store.drop("xcpng-lab", List.of(migrating));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A deferral landing while a drain runs must not be lost.
     *
     * <p>The fourth site of the non-atomic shape, and the one
     * {@link #aDrainConcurrentWithADropIsNotLost} cannot catch: that test defers before it starts the
     * concurrent work, so the defer and the drain never overlap. Here they are released together.
     * {@code refs} drains on the way in, so whichever order the two land in, the ref has to be readable
     * afterwards: either the drain already took it, or it is still pending and the next drain takes it.
     */
    @Test
    void aDeferConcurrentWithADrainIsNotLost(JenkinsRule r) throws Exception {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 300; i++) {
                String migrating = "OpaqueRef:deferred-" + i;

                CountDownLatch go = new CountDownLatch(1);
                Future<?> deferring = pool.submit(() -> {
                    awaitQuietly(go);
                    XcpngLeakedVmStore.deferMigration("xcpng-lab", List.of(migrating));
                });
                Future<?> draining = pool.submit(() -> {
                    awaitQuietly(go);
                    store.refs("xcpng-lab");
                });
                go.countDown();
                deferring.get(30, TimeUnit.SECONDS);
                draining.get(30, TimeUnit.SECONDS);

                assertTrue(
                        store.refs("xcpng-lab").contains(migrating),
                        "a deferral overlapping a drain must still reach the store, iteration " + i);
                store.drop("xcpng-lab", List.of(migrating));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A cloud whose {@code config.xml} carries no {@code <name>} must not turn a leak into a
     * {@code NullPointerException}. {@code ConcurrentHashMap} rejects a null key, and the throw would land on
     * the cleanup path of a failed provision, replacing the failure being handled and swallowing its warning.
     * The ref is unkeyable either way; the warning about the real failure is what matters.
     */
    @Test
    void anUnnamedCloudIsRefusedRatherThanThrowing(JenkinsRule r) {
        XcpngLeakedVmStore store = XcpngLeakedVmStore.get();

        assertFalse(store.record(null, "OpaqueRef:orphan"), "a nameless cloud cannot record, and must say so");
        assertTrue(store.refs(null).isEmpty(), "reading a nameless cloud must be empty, not an exception");
        assertDoesNotThrow(
                () -> store.drop(null, List.of("OpaqueRef:orphan")), "dropping for a nameless cloud must not throw");
    }

    /** {@link CountDownLatch#await()} without the checked exception, for use inside a submitted task. */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * A controller upgrading into #149 has its refs on the cloud, in {@code config.xml}. Deserializing such a
     * cloud must hand them to the store, and the cloud must stop writing the element back out -- otherwise the
     * same refs would migrate again on the next load and the two copies could diverge.
     */
    @Test
    void aPre149ConfigMigratesItsRefsIntoTheStore(JenkinsRule r) {
        String legacyXml = """
                <io.jenkins.plugins.xcpng.XcpngCloud>
                  <name>xcpng-legacy</name>
                  <poolUrl>https://pool.example.test</poolUrl>
                  <credentialsId>cred</credentialsId>
                  <maxInstances>2</maxInstances>
                  <templates/>
                  <leakedVmRefs>
                    <string>OpaqueRef:legacy-1</string>
                    <string>OpaqueRef:legacy-2</string>
                  </leakedVmRefs>
                </io.jenkins.plugins.xcpng.XcpngCloud>
                """;

        XcpngCloud migrated = (XcpngCloud) Jenkins.XSTREAM2.fromXML(legacyXml);
        assertNotNull(migrated);
        assertEquals(
                Set.of("OpaqueRef:legacy-1", "OpaqueRef:legacy-2"),
                migrated.leakedVmRefs(),
                "refs an older controller recorded must not be lost on upgrade");

        assertFalse(
                Jenkins.XSTREAM2.toXML(migrated).contains("leakedVmRefs"),
                "a migrated cloud must stop carrying the set in its own configuration");
    }
}
