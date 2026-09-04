package io.jenkins.plugins.xcpng.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link HypervisorClient} for testing the Jenkins half without a pool.
 *
 * <p>It records the verb sequence in {@link #calls()} so a provisioning test can assert that a clone
 * was started, then destroyed with its disks, in that order. It is not a simulation of XCP-ng; the
 * VBD/VDI/VM teardown ordering that matters inside the real client is tested against JSON fixtures,
 * not here.
 */
public final class FakeHypervisorClient implements HypervisorClient {

    private final Set<String> knownTemplates;
    /**
     * The verb log, on a copy-on-write list rather than an {@code ArrayList}.
     *
     * <p>Provisioning runs on whatever thread core is connecting the computer on, so a test asserting on
     * this log is almost always reading it from a different thread from the one writing it. An
     * {@code ArrayList} there is a data race: a reader can see a torn list, or throw
     * {@code ConcurrentModificationException} mid-iteration, and either failure would read as a bug in the
     * code under test. Writes are rare and reads are frequent, which is what copy-on-write is for.
     */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /**
     * The rest of the mutable state, guarded by this object's monitor.
     *
     * <p>Two launcher threads can be in {@code cloneFromTemplate} at once, because a warm pool registers
     * its spares together and core connects each on its own thread. Unguarded, {@code ++cloneCounter} can
     * hand both clones the same VM reference and concurrent {@code HashMap} writes can corrupt the map, so
     * every method that touches these is synchronized. {@link #calls} stays copy-on-write on top of that,
     * for the different reason given above: a caller iterates the list it is handed outside this monitor.
     */
    private final Map<String, VmState> states = new HashMap<>();

    private int cloneCounter = 0;
    private boolean pingFails = false;
    private int pingSuccessesBeforeFailing = -1;
    private boolean resolveFailsAtTransport = false;
    private boolean startFails = false;
    private boolean destroyFails = false;
    private String assignIpOnStart = null;
    private ProvisionSpec lastSpec = null;

    public FakeHypervisorClient(String... templates) {
        this.knownTemplates = new LinkedHashSet<>(List.of(templates));
    }

    /** Make {@link #ping()} throw, to test the failure path of a connection check. */
    public synchronized FakeHypervisorClient failPing() {
        this.pingFails = true;
        return this;
    }

    /**
     * Let {@link #ping()} succeed {@code successes} times and throw after that, so a caller that pings,
     * does something, and pings again can be given a pool that disappears between the two.
     */
    public synchronized FakeHypervisorClient failPingAfter(int successes) {
        this.pingSuccessesBeforeFailing = successes;
        return this;
    }

    /**
     * Make {@link #resolveTemplate} fail the way a dropped connection does rather than the way an absent
     * name does, whatever templates this fake knows. The two are indistinguishable from the exception --
     * both are a {@link HypervisorException} with a null error code -- which is exactly why a caller has
     * to tell them apart some other way.
     */
    public synchronized FakeHypervisorClient failResolveAtTransport() {
        this.resolveFailsAtTransport = true;
        return this;
    }

    /** Have a started VM report this IP, to stand in for guest tools writing an address. */
    public synchronized FakeHypervisorClient reportIpOnStart(String ip) {
        this.assignIpOnStart = ip;
        return this;
    }

    /** Make {@link #start} throw after the clone exists, to test failed-provision cleanup. */
    public synchronized FakeHypervisorClient failStart() {
        this.startFails = true;
        return this;
    }

    /**
     * Make {@link #destroyWithDisks} throw <em>after</em> recording the attempt, to test a teardown whose
     * destroy fails: the caller must still complete (remove the node, log) rather than leave it half-removed.
     */
    public synchronized FakeHypervisorClient failDestroy() {
        this.destroyFails = true;
        return this;
    }

    /**
     * Stop {@link #destroyWithDisks} throwing, so a later call succeeds. Models a pool that was briefly
     * unreachable during a teardown recovering by the time the leaked-VM sweep retries the destroy.
     */
    public synchronized FakeHypervisorClient recoverDestroy() {
        this.destroyFails = false;
        return this;
    }

    /** The verbs invoked so far, in order. */
    public List<String> calls() {
        return Collections.unmodifiableList(calls);
    }

    /**
     * Every verb below is a blocking HTTP call in the real client, and {@code HttpClient.send} throws
     * immediately when the calling thread's interrupt flag is already set. The fake models that, so a
     * cleanup path that runs its destroy on an interrupted thread fails here exactly as it would against a
     * pool, and records no call -- rather than passing a test vacuously.
     */
    private static void checkNotInterrupted(String verb) {
        if (Thread.currentThread().isInterrupted()) {
            throw new HypervisorException(verb + " called on an interrupted thread");
        }
    }

    /** The spec passed to the most recent {@link #cloneFromTemplate}, to assert the seed it carried. */
    public synchronized ProvisionSpec lastSpec() {
        return lastSpec;
    }

    @Override
    public synchronized VmRef resolveTemplate(String name) {
        checkNotInterrupted("resolveTemplate");
        calls.add("resolveTemplate:" + name);
        if (resolveFailsAtTransport) {
            throw new HypervisorException("VM.get_by_name_label: transport error: connection reset");
        }
        if (!knownTemplates.contains(name)) {
            throw new HypervisorException("no template named '" + name + "'");
        }
        return new VmRef("template/" + name);
    }

    @Override
    public synchronized VmRef cloneFromTemplate(VmRef template, ProvisionSpec spec) {
        checkNotInterrupted("cloneFromTemplate");
        this.lastSpec = spec;
        VmRef clone = new VmRef("vm/" + spec.name() + "/" + (++cloneCounter));
        calls.add("cloneFromTemplate:" + template.value() + "->" + clone.value());
        states.put(clone.value(), VmState.HALTED);
        return clone;
    }

    @Override
    public synchronized void start(VmRef vm) {
        checkNotInterrupted("start");
        calls.add("start:" + vm.value());
        if (startFails) {
            throw new HypervisorException("start failed");
        }
        states.put(vm.value(), VmState.RUNNING);
    }

    @Override
    public synchronized void clearGuestSecret(VmRef vm) {
        checkNotInterrupted("clearGuestSecret");
        calls.add("clearGuestSecret:" + vm.value());
    }

    @Override
    public synchronized Optional<String> primaryIpAddress(VmRef vm) {
        checkNotInterrupted("primaryIpAddress");
        if (assignIpOnStart != null && states.get(vm.value()) == VmState.RUNNING) {
            return Optional.of(assignIpOnStart);
        }
        return Optional.empty();
    }

    @Override
    public synchronized VmState state(VmRef vm) {
        checkNotInterrupted("state");
        return states.getOrDefault(vm.value(), VmState.UNKNOWN);
    }

    @Override
    public synchronized void stop(VmRef vm) {
        checkNotInterrupted("stop");
        calls.add("stop:" + vm.value());
        states.put(vm.value(), VmState.HALTED);
    }

    @Override
    public synchronized void destroyWithDisks(VmRef vm) {
        checkNotInterrupted("destroyWithDisks");
        calls.add("destroyWithDisks:" + vm.value());
        if (destroyFails) {
            throw new HypervisorException("destroy failed");
        }
        states.remove(vm.value());
    }

    @Override
    public synchronized void ping() {
        checkNotInterrupted("ping");
        calls.add("ping");
        if (pingSuccessesBeforeFailing >= 0) {
            if (pingSuccessesBeforeFailing == 0) {
                throw new HypervisorException("ping: transport error: connection reset");
            }
            pingSuccessesBeforeFailing--;
            return;
        }
        if (pingFails) {
            throw new HypervisorException("cannot reach pool");
        }
    }

    @Override
    public void close() {
        calls.add("close");
    }
}
