package io.jenkins.plugins.xcpng.client;

/**
 * Backend-neutral power state of a VM.
 *
 * <p>The Xen Orchestra backend maps the VM record's {@code power_state} strings onto these; anything it
 * does not recognise becomes {@link #UNKNOWN} rather than leaking a backend-specific value to callers.
 */
public enum VmState {
    HALTED,
    RUNNING,
    PAUSED,
    SUSPENDED,
    UNKNOWN
}
