package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * How a connection decides whether the appliance at the far end is the one it was configured for: the two
 * values a cloud carries for that, travelling together so that every path which opens a client receives
 * both or neither.
 *
 * <p>Three modes, and at most one value is meant to be set. Neither means the JVM trust store and its
 * hostname check. A {@linkplain #certificateFingerprint() fingerprint} pins one leaf certificate. {@linkplain
 * #caCertificates() CA certificates} trust exactly the authorities given, and nothing else, with the hostname
 * check kept (#172). Setting both is not a fourth mode: {@link TrustedHttpClients} refuses it rather than
 * choosing one, because either choice would quietly ignore half of what an operator wrote.
 *
 * <p>Nothing is parsed here. A malformed value is refused when a client is built from it, which is the
 * point every caller already treats as "these parameters cannot reach the appliance"; parsing at
 * construction would instead throw from places that only carry the value around, such as an agent's
 * connection snapshot. In memory only and never persisted: the cloud, the agent and the leaked-VM record
 * each keep the two strings as ordinary fields, so a {@code config.xml} written before this type existed
 * reads exactly as it did.
 *
 * @param certificateFingerprint SHA-256 fingerprint of the one certificate to accept, in any form {@link
 *     CertificateFingerprint#normalize} reads, or null.
 * @param caCertificates one or more PEM certificates of the authorities to trust, or null.
 */
public record PoolTrust(
        @CheckForNull String certificateFingerprint,
        @CheckForNull String caCertificates) {

    /** The JVM trust store and its hostname check: no fingerprint, no CA. */
    public static final PoolTrust JVM_DEFAULT = new PoolTrust(null, null);

    /** Blank means absent, for both values, so a form submitting empty fields builds {@link #JVM_DEFAULT}. */
    public PoolTrust {
        certificateFingerprint =
                certificateFingerprint == null || certificateFingerprint.isBlank() ? null : certificateFingerprint;
        caCertificates = caCertificates == null || caCertificates.isBlank() ? null : caCertificates;
    }

    /** Trust exactly the certificate with this fingerprint; null or blank is {@link #JVM_DEFAULT}. */
    @NonNull
    public static PoolTrust pinned(@CheckForNull String certificateFingerprint) {
        return new PoolTrust(certificateFingerprint, null);
    }

    /** Trust exactly these authorities; null or blank is {@link #JVM_DEFAULT}. */
    @NonNull
    public static PoolTrust anchoredAt(@CheckForNull String caCertificates) {
        return new PoolTrust(null, caCertificates);
    }

    /** Whether a fingerprint is set. */
    public boolean isPinned() {
        return certificateFingerprint != null;
    }

    /** Whether CA certificates are set. */
    public boolean isAnchored() {
        return caCertificates != null;
    }

    /**
     * The fingerprint, and whether CA certificates are present, but never the PEM itself: this lands in log
     * lines and failure messages, where a multi-line certificate is noise. A public certificate is not a
     * secret, so this is about legibility, not exposure.
     */
    @Override
    @NonNull
    public String toString() {
        if (certificateFingerprint == null && caCertificates == null) {
            return "PoolTrust[JVM trust store]";
        }
        return "PoolTrust[fingerprint=" + certificateFingerprint + ", caCertificates="
                + (caCertificates == null ? "none" : "set") + "]";
    }
}
