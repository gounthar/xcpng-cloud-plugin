package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Opaque handle to a VM or template on the backend.
 *
 * <p>Callers never parse the wrapped value. For the XAPI backend it happens to be an opaque object
 * reference, but nothing outside {@link HypervisorClient} implementations may depend on that: a Xen
 * Orchestra backend would put a different string here. Treat it as a token you got from the client
 * and hand back to the client.
 */
public record VmRef(@NonNull String value) {

    public VmRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("VmRef value must be non-blank");
        }
    }

    @Override
    public String toString() {
        return "VmRef[" + value + "]";
    }
}
