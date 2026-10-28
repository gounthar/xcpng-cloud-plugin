package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.xcpng.client.PoolTrust;
import io.jenkins.plugins.xcpng.client.VmRef;
import java.util.Objects;

/**
 * One VM a teardown failed to destroy, together with the connection it was provisioned over.
 *
 * <p>The connection travels with the ref because a ref is only meaningful to the connection that minted it,
 * and handing it to any other is worse than useless: it can come back as a false success. The Xen Orchestra
 * backend reads "no such object" (a 404) as "already destroyed", as the removed XAPI backend did with
 * {@code HANDLE_INVALID}, which is right for the race it was written for (#145) and wrong for a ref the
 * connection has never heard of.
 * Measured on the lab pool on 2026-09-19 (#223): XAPI answers a Xen Orchestra uuid with
 * {@code HANDLE_INVALID ["VM", "<uuid>"]}, exactly the shape {@code XapiClient} treated as already gone, so a
 * sweep aimed at the wrong backend dropped the ref as reclaimed while the VM kept running. A ref handed to a
 * different pool fails the same way. So {@link XcpngCloud#sweepLeakedVms} only ever hands a ref back to the
 * connection recorded here.
 *
 * <p>An entry with no {@link #getPoolUrl() pool URL} records no connection. It is a ref from before #223,
 * when only the bare ref was stored, or from an agent that predates the connection snapshot; either way the
 * cloud's current connection is the only one there is, and the sweep uses it only when its backend matches
 * {@link #getBackend()}.
 *
 * <p>Immutable, and a plain class rather than a record: this is persisted by XStream in
 * {@link XcpngLeakedVmStore}'s file, and a class with ordinary fields is the shape that file has always had.
 */
public final class XcpngLeakedVm {

    private final String vmRef;
    private final XcpngBackend backend;
    private final String poolUrl;
    private final String credentialsId;
    private final String certificateFingerprint;

    /**
     * CA certificates the connection trusted (#172), or null. An entry written before this field existed loads
     * with null, which is what every such entry's cloud had.
     */
    private final String caCertificates;

    /**
     * Whether the teardown that recorded this entry was refused by the #48 guard: the pool read the VM
     * {@code Halted} while its agent was still connected (#306). A held entry is destroyed only once the pool
     * reads it in a state Xen Orchestra shuts down before deleting. An entry written before this field existed
     * loads false, which is right: no teardown before it held anything.
     *
     * <p>Not part of {@link #equals}: it says how to sweep the VM, not which VM it is.
     */
    private final boolean held;

    /**
     * The name of the agent whose teardown recorded this entry, which is also the VM's name-label on the pool,
     * or null. An entry written before this field existed loads with null, as does one recorded where no agent
     * was at hand. It is what lets an administrator find on the pool a VM this plugin can no longer reach, since
     * the ref alone names nothing a person recognises (#330).
     *
     * <p>Not part of {@link #equals}, like {@link #held}: it describes the VM rather than identifying it.
     */
    private final String nodeName;

    private XcpngLeakedVm(
            @NonNull String vmRef,
            @NonNull XcpngBackend backend,
            @CheckForNull String poolUrl,
            @CheckForNull String credentialsId,
            @CheckForNull String certificateFingerprint,
            @CheckForNull String caCertificates,
            boolean held,
            @CheckForNull String nodeName) {
        this.vmRef = Objects.requireNonNull(vmRef, "vmRef");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.poolUrl = poolUrl;
        this.credentialsId = credentialsId;
        this.certificateFingerprint = certificateFingerprint;
        this.caCertificates = caCertificates;
        this.held = held;
        this.nodeName = nodeName;
    }

    /**
     * A leak recorded with the connection its VM was provisioned over. A null {@code poolUrl} makes it an
     * entry with no connection: that is what an agent predating the snapshot has to offer, and it is kept
     * with the backend the agent reports rather than one guessed from the ref.
     */
    @NonNull
    static XcpngLeakedVm of(
            @NonNull String vmRef,
            @CheckForNull XcpngBackend backend,
            @CheckForNull String poolUrl,
            @CheckForNull String credentialsId,
            @CheckForNull String certificateFingerprint) {
        return of(vmRef, backend, poolUrl, credentialsId, certificateFingerprint, null);
    }

    /** As above, for a connection that may trust CA certificates rather than, or as well as, a fingerprint. */
    @NonNull
    static XcpngLeakedVm of(
            @NonNull String vmRef,
            @CheckForNull XcpngBackend backend,
            @CheckForNull String poolUrl,
            @CheckForNull String credentialsId,
            @CheckForNull String certificateFingerprint,
            @CheckForNull String caCertificates) {
        if (poolUrl == null) {
            return new XcpngLeakedVm(vmRef, XcpngBackend.resolve(backend), null, null, null, null, false, null);
        }
        return new XcpngLeakedVm(
                vmRef,
                XcpngBackend.resolve(backend),
                poolUrl,
                credentialsId,
                certificateFingerprint,
                caCertificates,
                false,
                null);
    }

    /** A leak recorded with the cloud's connection as it is now, for a VM provisioned under that connection. */
    @NonNull
    static XcpngLeakedVm of(@NonNull String vmRef, @NonNull XcpngCloud cloud) {
        return of(
                vmRef,
                cloud.getBackend(),
                cloud.getPoolUrl(),
                cloud.getCredentialsId(),
                cloud.getCertificateFingerprint(),
                cloud.getCaCertificates());
    }

    /**
     * A bare ref stored before #223, which recorded no connection. Its backend is read off its shape, the
     * only evidence left: XAPI handles are {@code OpaqueRef:} strings and Xen Orchestra's are uuids.
     */
    @NonNull
    static XcpngLeakedVm legacy(@NonNull String vmRef) {
        XcpngBackend inferred = vmRef.startsWith(VmRef.XAPI_REF_PREFIX) ? XcpngBackend.XAPI : XcpngBackend.XO;
        return new XcpngLeakedVm(vmRef, inferred, null, null, null, null, false, null);
    }

    @NonNull
    public String getVmRef() {
        return vmRef;
    }

    @NonNull
    public XcpngBackend getBackend() {
        return backend;
    }

    /** The pool (or appliance) URL the VM was provisioned against; null when no connection was recorded. */
    @CheckForNull
    public String getPoolUrl() {
        return poolUrl;
    }

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @CheckForNull
    public String getCertificateFingerprint() {
        return certificateFingerprint;
    }

    @CheckForNull
    public String getCaCertificates() {
        return caCertificates;
    }

    /** The agent this VM was built for, which is also its name-label on the pool; null when not recorded. */
    @CheckForNull
    public String getNodeName() {
        return nodeName;
    }

    /** This entry, held: the sweep reads the VM's state before destroying it. See {@link #isHeld()}. */
    @NonNull
    XcpngLeakedVm asHeld() {
        return held ? this : with(true, nodeName);
    }

    /** This entry, naming the agent it was recorded for. See {@link #getNodeName()}. */
    @NonNull
    XcpngLeakedVm named(@CheckForNull String nodeName) {
        return Objects.equals(this.nodeName, nodeName) ? this : with(held, nodeName);
    }

    /**
     * This entry with what {@code other}, an equal entry recorded again, adds to it: held if either is, since a
     * refusal by the #48 guard is never taken back (#306), and the first node name either carries.
     */
    @NonNull
    XcpngLeakedVm mergedWith(@NonNull XcpngLeakedVm other) {
        return with(held || other.held, nodeName != null ? nodeName : other.nodeName);
    }

    /** Whether this entry and {@code other} differ in a field {@link #equals} does not compare. */
    boolean describedDifferentlyFrom(@NonNull XcpngLeakedVm other) {
        return held != other.held || !Objects.equals(nodeName, other.nodeName);
    }

    private XcpngLeakedVm with(boolean held, @CheckForNull String nodeName) {
        return new XcpngLeakedVm(
                vmRef, backend, poolUrl, credentialsId, certificateFingerprint, caCertificates, held, nodeName);
    }

    /**
     * Whether the #48 guard refused this VM's teardown, so a sweep must not delete it while the pool still
     * reads it {@code Halted} (#306).
     */
    boolean isHeld() {
        return held;
    }

    /** Whether this entry carries its own connection, rather than depending on the cloud's current one. */
    boolean hasConnection() {
        return poolUrl != null;
    }

    /** Whether this entry was recorded over exactly the connection {@code cloud} is configured with now. */
    boolean sameConnectionAs(@NonNull XcpngCloud cloud) {
        return hasConnection()
                && backend == cloud.getBackend()
                && poolUrl.equals(cloud.getPoolUrl())
                && Objects.equals(credentialsId, cloud.getCredentialsId())
                && Objects.equals(certificateFingerprint, cloud.getCertificateFingerprint())
                && Objects.equals(caCertificates, cloud.getCaCertificates());
    }

    /** The connection alone, for grouping entries so a sweep opens one client per connection. */
    @NonNull
    Connection connection() {
        return new Connection(backend, poolUrl, credentialsId, new PoolTrust(certificateFingerprint, caCertificates));
    }

    /** A connection as a map key. In memory only: never persisted, so a record is safe here. */
    record Connection(
            @NonNull XcpngBackend backend,
            @CheckForNull String poolUrl,
            @CheckForNull String credentialsId,
            @NonNull PoolTrust trust) {}

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof XcpngLeakedVm other)) {
            return false;
        }
        return vmRef.equals(other.vmRef)
                && backend == other.backend
                && Objects.equals(poolUrl, other.poolUrl)
                && Objects.equals(credentialsId, other.credentialsId)
                && Objects.equals(certificateFingerprint, other.certificateFingerprint)
                && Objects.equals(caCertificates, other.caCertificates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vmRef, backend, poolUrl, credentialsId, certificateFingerprint, caCertificates);
    }

    @Override
    public String toString() {
        return vmRef
                + (hasConnection() ? " (" + backend + " at " + poolUrl + ")" : " (" + backend + ", no connection)")
                + (held ? " [held]" : "");
    }
}
