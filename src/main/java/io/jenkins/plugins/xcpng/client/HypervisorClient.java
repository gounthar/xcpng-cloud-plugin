package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * The lifecycle verbs the plugin needs from a hypervisor, named in plugin terms rather than XAPI
 * terms. {@code XapiClient} implements this today; a Xen Orchestra REST backend can sit beside it
 * later without the provisioning code changing.
 *
 * <p>The interface deliberately absorbs where backends diverge, rather than leaking it:
 * <ul>
 *   <li><b>Async/task polling.</b> {@link #cloneFromTemplate} is synchronous from the caller's view;
 *       each backend owns its own polling loop. This is the single most important anti-leak decision.
 *   <li><b>Placement.</b> {@link ProvisionSpec#placementHint()} is an opaque string; null lets the
 *       pool schedule.
 *   <li><b>Auth/session.</b> Re-login and master redirects stay inside the implementation and are not
 *       on this interface at all.
 * </ul>
 *
 * <p>Do not over-abstract this: no capability negotiation, no factory-of-factories, no generic
 * {@code execute()}. Eight verbs, closed over {@link AutoCloseable} so a session is released.
 */
public interface HypervisorClient extends AutoCloseable {

    /**
     * Resolve a template by name to a handle the other verbs accept.
     *
     * @throws HypervisorException if no template with that name exists, or the named object is not a
     *     template (an ordinary VM by that name does not count).
     */
    @NonNull
    VmRef resolveTemplate(@NonNull String name);

    /**
     * Clone a VM from a template and apply {@code spec}. Synchronous from the caller's view: returns
     * only once the clone exists and is addressable, however much task polling that took underneath.
     */
    @NonNull
    VmRef cloneFromTemplate(@NonNull VmRef template, @NonNull ProvisionSpec spec);

    /** Power on a halted VM. */
    void start(@NonNull VmRef vm);

    /**
     * The VM's primary IP, if one is known yet. Empty until an in-guest agent reports an address to
     * the backend; a VM without guest tools returns empty for its whole life. The inbound launcher
     * does not need this, but an SSH launcher and the latency probe do.
     */
    @NonNull
    Optional<String> primaryIpAddress(@NonNull VmRef vm);

    /** Current power state, or {@link VmState#UNKNOWN} for a value the backend does not map. */
    @NonNull
    VmState state(@NonNull VmRef vm);

    /** Request a clean shutdown. */
    void stop(@NonNull VmRef vm);

    /**
     * Destroy the VM and every disk that was attached to it. Implementations must capture the
     * VBD-to-VDI references <em>before</em> destroying the VM, because destroying it removes the VBDs
     * and orphans the VDIs otherwise.
     */
    void destroyWithDisks(@NonNull VmRef vm);

    /**
     * Verify connectivity and authentication against the pool. Throws on failure so a UI
     * test-connection button can report why. Does not mutate anything.
     */
    void ping();

    /** Release the session. Overridden to not throw the checked exception {@link AutoCloseable} declares. */
    @Override
    void close();
}
