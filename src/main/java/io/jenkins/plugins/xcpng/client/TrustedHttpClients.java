package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The shared {@link HttpClient} instances every transport in this package dials through, and the only
 * place TLS trust is decided.
 *
 * <p>There are exactly two modes and neither of them is "accept anything". With no pinned fingerprint the
 * JVM's own trust store and hostname check apply, which is what a pool or appliance holding a certificate
 * from a real CA wants. With a fingerprint, the connection succeeds only against that one certificate. The
 * trust-all context this package used to carry is gone: "trust this pool's self-signed certificate" and
 * "trust every certificate in the world" were the same switch, and only the second of those is what the
 * code did.
 *
 * <p>Extracted from {@code HttpTransport} when a second transport ({@link HttpRestTransport}, speaking to
 * Xen Orchestra) needed the same decision. Two transports each building their own trust would be two places
 * to get it wrong, and only one of them would be covered by {@code HttpTransportPinningTest}.
 */
final class TrustedHttpClients {

    // One HttpClient per operation would allocate a fresh thread and connection pool each time. These are
    // immutable and thread-safe, so share them across the JVM: the verified client as a singleton, and one
    // pinned client per distinct fingerprint. The pinned map is keyed by fingerprint rather than by URL
    // because the certificate, not the address, is what the client is built around -- two clouds pointing
    // at the same pool share one client, and a re-pinned pool gets a new one.
    private static final HttpClient SHARED =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private static final Map<String, HttpClient> PINNED = new ConcurrentHashMap<>();

    private TrustedHttpClients() {}

    /**
     * @param certificateFingerprint SHA-256 fingerprint of the certificate the far end is expected to
     *     present, in any form {@link CertificateFingerprint#normalize} accepts. Null or blank means
     *     ordinary verification against the JVM trust store.
     */
    static HttpClient forFingerprint(@CheckForNull String certificateFingerprint) {
        return certificateFingerprint == null || certificateFingerprint.isBlank()
                ? SHARED
                : PINNED.computeIfAbsent(
                        CertificateFingerprint.normalize(certificateFingerprint), TrustedHttpClients::pinnedClient);
    }

    /**
     * A client that completes a handshake only against the certificate with this fingerprint.
     *
     * <p>The pin is the whole of the identity check; the certificate does not also have to name the host.
     * An exact leaf match already identifies the server more tightly than a CA signature plus a name does,
     * so on a pinned connection a hostname check can only refuse a certificate the pin has accepted. It
     * did, for the certificate every Xen Orchestra appliance generates for itself, which carries no CN and
     * no subjectAltName and so matches no address at all (#229).
     *
     * <p>How the check is left out matters. {@code java.net.http.HttpClient} forces the endpoint-
     * identification algorithm to HTTPS whatever a caller sets, in {@code AbstractAsyncSSLConnection},
     * unless the JVM-wide {@code jdk.internal.httpclient.disableHostnameVerification} property is set, and
     * setting that would drop the check for every HTTP client in the controller. It is not set. The check
     * is instead skipped by the trust manager's type: JSSE wraps a plain {@link X509TrustManager} in an
     * {@code AbstractTrustManagerWrapper} that runs the hostname check after it, and uses an {@link
     * X509ExtendedTrustManager} as it is, leaving identity to that trust manager. {@link PinnedTrustManager}
     * is the second kind, and checks the pin only. An earlier version of this comment said the check could
     * not be avoided here; that was true of the plain kind this class used to be.
     *
     * <p>Unpinned connections go through {@link #SHARED}, which keeps the JVM trust store and its hostname
     * check. Nothing here changes those.
     *
     * <p>The scan flags every {@code SSLContext#init}, because that call is how TLS verification is
     * normally switched off; it does not read the trust manager it is handed. This one narrows trust
     * rather than widening it: {@link PinnedTrustManager} accepts a single certificate where the JVM
     * default accepts every public CA. Suppressed rather than dismissed through the API so the reasoning
     * sits beside the code. See #142.
     */
    @SuppressWarnings("lgtm[jenkins/unsafe-calls]") // Pins one certificate; strictly narrower than the JVM default.
    private static HttpClient pinnedClient(String fingerprint) {
        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new PinnedTrustManager(fingerprint)}, new SecureRandom());
        } catch (java.security.GeneralSecurityException e) {
            throw new HypervisorException("cannot build a certificate-pinning SSL context: " + e.getMessage(), e);
        }
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .sslContext(ctx)
                .build();
    }

    /**
     * Accepts one certificate and no other. Unlike the trust manager it replaced, every method here can
     * fail: an empty {@code checkServerTrusted} is what made the old one accept the world.
     *
     * <p>Extends {@link X509ExtendedTrustManager} rather than implementing {@link X509TrustManager} so that
     * JSSE does not add its own hostname check after this one; see {@link #pinnedClient}. The socket and
     * engine overloads are the ones JSSE actually calls, and each applies the same pin as the plain one.
     */
    private static final class PinnedTrustManager extends X509ExtendedTrustManager {

        private final String expected;

        PinnedTrustManager(String expected) {
            this.expected = expected;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // This manager is only ever installed on a client. Reached only if it were misused as a server.
            throw new CertificateException("this trust manager never authenticates a client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("the pool presented no certificate");
            }
            String actual = CertificateFingerprint.of(chain[0]);
            if (!expected.equals(actual)) {
                // Name both, so an operator reading the failure can tell a replaced certificate from a
                // mistyped pin without going to the pool to look.
                throw new CertificateException("the pool's certificate does not match the pinned fingerprint."
                        + " Expected " + expected + ", got " + actual
                        + ". If the pool's certificate was replaced, confirm the new one and update the cloud's"
                        + " Certificate fingerprint field.");
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            // A pinned certificate is not reached through an issuer, so there are none to advertise.
            return new X509Certificate[0];
        }
    }
}
