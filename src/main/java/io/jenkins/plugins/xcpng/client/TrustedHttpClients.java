package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
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
     * <p>Hostname verification still applies, and it is not optional here even if we wanted it to be:
     * {@code java.net.http.HttpClient} overwrites whatever endpoint-identification algorithm a caller
     * sets, in {@code AbstractAsyncSSLConnection} lines 138-139 of the JDK source, unless the JVM-wide
     * {@code jdk.internal.httpclient.disableHostnameVerification} property is set. Setting that property
     * would disable the check for every HTTP client in the controller, which is a far larger hole than
     * the one being closed, so it is not set and the algorithm is left alone rather than assigned a value
     * that would be silently discarded. The trust-all path this replaced set it to null and was subject to
     * exactly the same override, so hostname verification was in force there too, whatever it looked like.
     *
     * <p>The two checks therefore stack: a connection succeeds only if the certificate both matches the
     * pinned fingerprint and identifies the host being dialled. XCP-ng's generated certificate carries the
     * host's address in its subject and its subjectAltName -- measured on the lab pool, {@code
     * CN=192.168.1.87} with {@code IP Address:192.168.1.87} -- so a pool reached at the address its
     * certificate names satisfies both. A pool reached under some other name needs a certificate that
     * says so, which is a fair thing to require and was already required before this change.
     *
     * <p>The scan flags every {@code SSLContext#init}, because that call is how TLS verification is
     * normally switched off; it does not read the trust manager it is handed. This one narrows trust
     * rather than widening it: {@link PinnedTrustManager} accepts a single certificate where the JVM
     * default accepts every public CA, and hostname verification still applies on top. Suppressed
     * rather than dismissed through the API so the reasoning sits beside the code. See #142.
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
     */
    private static final class PinnedTrustManager implements X509TrustManager {

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
        public X509Certificate[] getAcceptedIssuers() {
            // A pinned certificate is not reached through an issuer, so there are none to advertise.
            return new X509Certificate[0];
        }
    }
}
