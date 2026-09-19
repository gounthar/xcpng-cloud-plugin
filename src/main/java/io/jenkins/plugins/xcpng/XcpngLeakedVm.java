package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.xcpng.client.XapiClient;
import java.util.Objects;

/**
 * One VM a teardown failed to destroy, together with the connection it was provisioned over.
 *
 * <p>The connection travels with the ref because a ref is only meaningful to the connection that minted it,
 * and handing it to any other is worse than useless: it can come back as a false success. Both backends read
 * "no such object" as "already destroyed" -- {@code HANDLE_INVALID} on XAPI, 404 on Xen Orchestra -- which is
 * right for the race it was written for (#145) and wrong for a ref the connection has never heard of.
 * Measured on the lab pool on 2026-09-19 (#223): XAPI answers a Xen Orchestra uuid with
 * {@code HANDLE_INVALID ["VM", "<uuid>"]}, exactly the shape {@code XapiClient} treats as already gone, so a
 * sweep aimed at the wrong backend drops the ref as reclaimed while the VM keeps running. A ref handed to a
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

    private XcpngLeakedVm(
            @NonNull String vmRef,
            @NonNull XcpngBackend backend,
            @CheckForNull String poolUrl,
            @CheckForNull String credentialsId,
            @CheckForNull String certificateFingerprint) {
        this.vmRef = Objects.requireNonNull(vmRef, "vmRef");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.poolUrl = poolUrl;
        this.credentialsId = credentialsId;
        this.certificateFingerprint = certificateFingerprint;
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
        if (poolUrl == null) {
            return new XcpngLeakedVm(vmRef, XcpngBackend.resolve(backend), null, null, null);
        }
        return new XcpngLeakedVm(vmRef, XcpngBackend.resolve(backend), poolUrl, credentialsId, certificateFingerprint);
    }

    /** A leak recorded with the cloud's connection as it is now, for a VM provisioned under that connection. */
    @NonNull
    static XcpngLeakedVm of(@NonNull String vmRef, @NonNull XcpngCloud cloud) {
        return of(
                vmRef,
                cloud.getBackend(),
                cloud.getPoolUrl(),
                cloud.getCredentialsId(),
                cloud.getCertificateFingerprint());
    }

    /**
     * A bare ref stored before #223, which recorded no connection. Its backend is read off its shape, the
     * only evidence left: XAPI handles are {@code OpaqueRef:} strings and Xen Orchestra's are uuids.
     */
    @NonNull
    static XcpngLeakedVm legacy(@NonNull String vmRef) {
        XcpngBackend inferred = vmRef.startsWith(XapiClient.REF_PREFIX) ? XcpngBackend.XAPI : XcpngBackend.XO;
        return new XcpngLeakedVm(vmRef, inferred, null, null, null);
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
                && Objects.equals(certificateFingerprint, cloud.getCertificateFingerprint());
    }

    /** The connection alone, for grouping entries so a sweep opens one client per connection. */
    @NonNull
    Connection connection() {
        return new Connection(backend, poolUrl, credentialsId, certificateFingerprint);
    }

    /** A connection as a map key. In memory only: never persisted, so a record is safe here. */
    record Connection(
            @NonNull XcpngBackend backend,
            @CheckForNull String poolUrl,
            @CheckForNull String credentialsId,
            @CheckForNull String certificateFingerprint) {}

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
                && Objects.equals(certificateFingerprint, other.certificateFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vmRef, backend, poolUrl, credentialsId, certificateFingerprint);
    }

    @Override
    public String toString() {
        return vmRef
                + (hasConnection() ? " (" + backend + " at " + poolUrl + ")" : " (" + backend + ", no connection)");
    }
}
