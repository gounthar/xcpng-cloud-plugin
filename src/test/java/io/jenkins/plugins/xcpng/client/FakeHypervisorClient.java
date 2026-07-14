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

    @Override
    public VmRef resolveTemplate(String name) {
        calls.add("resolveTemplate:" + name);
        if (!knownTemplates.contains(name)) {
            throw new HypervisorException("no template named '" + name + "'");
        }
        return new VmRef("template/" + name);
    }

    @Override
    public VmRef cloneFromTemplate(VmRef template, ProvisionSpec spec) {
        VmRef clone = new VmRef("vm/" + spec.name() + "/" + (++cloneCounter));
        calls.add("cloneFromTemplate:" + template.value() + "->" + clone.value());
        states.put(clone.value(), VmState.HALTED);
        return clone;
    }

    @Override
    public void start(VmRef vm) {
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
        calls.add("stop:" + vm.value());
        states.put(vm.value(), VmState.HALTED);
    }

    @Override
    public void destroyWithDisks(VmRef vm) {
        calls.add("destroyWithDisks:" + vm.value());
        states.remove(vm.value());
    }

    @Override
    public void ping() {
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
