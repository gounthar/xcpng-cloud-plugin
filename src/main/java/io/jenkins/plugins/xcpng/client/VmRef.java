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

    /**
     * XAPI's handle prefix. Every ref the XAPI backend mints carries it and no Xen Orchestra id does, which
     * makes it the one fact about a ref's shape worth knowing outside a backend: a ref handed to the wrong
     * backend reads as already destroyed on both (#223), so each refuses the other's shape up front.
     *
     * <p>It lives here rather than on {@code XapiClient} because it outlived that class. Refs recorded
     * before the move to Xen Orchestra stay {@code OpaqueRef:} shaped in stored state, and the guard that
     * recognises them has to keep working after the backend that minted them is gone (#89).
     */
    public static final String XAPI_REF_PREFIX = "OpaqueRef:";

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
