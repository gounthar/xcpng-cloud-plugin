package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Which hypervisor API a cloud, an agent or a leaked-VM record was configured against.
 *
 * <p>Only {@link #XO} can be spoken. {@link #XAPI} survives as a value because it is persisted: a cloud,
 * an agent or a leaked-VM entry saved by an older release names it, or names nothing, which meant XAPI at
 * the time. Keeping the constant is what lets such a record load with its configuration intact and be
 * refused by name, rather than failing to deserialize. A cloud that fails to deserialize is dropped from
 * {@code Jenkins.clouds} by core's {@code RobustCollectionConverter}, and the next save of the clouds page
 * erases it for good, which is a worse outcome than the refusal it was meant to be.
 *
 * <p>Persisted by name in {@code config.xml}, so the constant names are configuration and cannot be
 * renamed without a migration.
 */
public enum XcpngBackend {

    /**
     * JSON-RPC to {@code /jsonrpc} on a pool master. <b>Removed</b> (#89): nothing in this plugin can speak
     * it any more. A record carrying it is refused where a client would be opened, and
     * {@link XcpngRemovedBackendMonitor} names the clouds and agents still configured for it.
     */
    XAPI,

    /**
     * The Xen Orchestra REST API under {@code /rest/v0} on an appliance, authenticated with a secret-text
     * token sent as {@code Cookie: authenticationToken=<token>}. Requires an appliance that routes
     * {@code PATCH /rest/v0/vms/{id}}, the call both the xenstore seed and the #28 secret scrub need; Test
     * connection checks for it.
     */
    XO;

    /** Whether this plugin can still open a client for this backend. */
    public boolean isSupported() {
        return this == XO;
    }

    /**
     * Resolve a <em>persisted</em> value. Null is the ordinary case rather than an error: a cloud, agent or
     * leaked-VM record saved before this field existed carries no value, and every such record was written
     * by a release that spoke XAPI and nothing else. So an absence reads as {@link #XAPI}, which is the
     * truth about that record, and the refusal then follows from it.
     *
     * <p>Do not use this for a value arriving from a form or a configuration-as-code document. An absence
     * there asks for the default, which is {@link #XO}; see {@code XcpngCloud.setBackend}.
     */
    @NonNull
    public static XcpngBackend resolve(@CheckForNull XcpngBackend backend) {
        return backend == null ? XAPI : backend;
    }
}
