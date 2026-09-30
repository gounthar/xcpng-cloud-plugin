package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Opaque handle to a VM or template on the backend.
 *
 * <p>Callers never parse the wrapped value. On the Xen Orchestra backend it happens to be the VM's XO id,
 * or a template's pool and uuid, and the removed XAPI backend put an opaque object reference here; nothing
 * outside {@link HypervisorClient} implementations may depend on either. Treat it as a token you got from
 * the client and hand back to the client.
 */
public record VmRef(@NonNull String value) {

    /**
     * XAPI's handle prefix. Every ref the XAPI backend minted carries it and no Xen Orchestra id does, which
     * made it the one fact about a ref's shape worth knowing outside a backend: a ref handed to the wrong
     * backend read as already destroyed on both (#223), so each refused the other's shape up front, and
     * {@link XoRestClient} still does.
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
