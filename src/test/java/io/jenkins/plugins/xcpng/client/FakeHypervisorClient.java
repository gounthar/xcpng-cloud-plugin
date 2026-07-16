package io.jenkins.plugins.xcpng.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final List<String> calls = new ArrayList<>();
    private final Map<String, VmState> states = new HashMap<>();
    private int cloneCounter = 0;
    private boolean pingFails = false;
    private boolean startFails = false;
    private String assignIpOnStart = null;
    private ProvisionSpec lastSpec = null;

    public FakeHypervisorClient(String... templates) {
        this.knownTemplates = new LinkedHashSet<>(List.of(templates));
    }

    /** Make {@link #ping()} throw, to test the failure path of a connection check. */
    public FakeHypervisorClient failPing() {
        this.pingFails = true;
        return this;
    }

    /** Have a started VM report this IP, to stand in for guest tools writing an address. */
    public FakeHypervisorClient reportIpOnStart(String ip) {
        this.assignIpOnStart = ip;
        return this;
    }

    /** Make {@link #start} throw after the clone exists, to test failed-provision cleanup. */
    public FakeHypervisorClient failStart() {
        this.startFails = true;
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
    public ProvisionSpec lastSpec() {
        return lastSpec;
    }

    @Override
    public VmRef resolveTemplate(String name) {
        checkNotInterrupted("resolveTemplate");
        calls.add("resolveTemplate:" + name);
        if (!knownTemplates.contains(name)) {
            throw new HypervisorException("no template named '" + name + "'");
        }
        return new VmRef("template/" + name);
    }

    @Override
    public VmRef cloneFromTemplate(VmRef template, ProvisionSpec spec) {
        checkNotInterrupted("cloneFromTemplate");
        this.lastSpec = spec;
        VmRef clone = new VmRef("vm/" + spec.name() + "/" + (++cloneCounter));
        calls.add("cloneFromTemplate:" + template.value() + "->" + clone.value());
        states.put(clone.value(), VmState.HALTED);
        return clone;
    }

    @Override
    public void start(VmRef vm) {
        checkNotInterrupted("start");
        calls.add("start:" + vm.value());
        if (startFails) {
            throw new HypervisorException("start failed");
        }
        states.put(vm.value(), VmState.RUNNING);
    }

    @Override
    public Optional<String> primaryIpAddress(VmRef vm) {
        if (assignIpOnStart != null && states.get(vm.value()) == VmState.RUNNING) {
            return Optional.of(assignIpOnStart);
        }
        return Optional.empty();
    }

    @Override
    public VmState state(VmRef vm) {
        return states.getOrDefault(vm.value(), VmState.UNKNOWN);
    }

    @Override
    public void stop(VmRef vm) {
        checkNotInterrupted("stop");
        calls.add("stop:" + vm.value());
        states.put(vm.value(), VmState.HALTED);
    }

    @Override
    public void destroyWithDisks(VmRef vm) {
        checkNotInterrupted("destroyWithDisks");
        calls.add("destroyWithDisks:" + vm.value());
        states.remove(vm.value());
    }

    @Override
    public void ping() {
        checkNotInterrupted("ping");
        calls.add("ping");
        if (pingFails) {
            throw new HypervisorException("cannot reach pool");
        }
    }

    @Override
    public void close() {
        calls.add("close");
    }
}
