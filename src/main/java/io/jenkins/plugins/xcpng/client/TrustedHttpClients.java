package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * is the second kind. An earlier version of this comment said the check could not be avoided here; that
     * was true of the plain kind this class used to be.
     *
     * <p>That wrapper also enforced the runtime's certificate algorithm policy, {@code
     * jdk.certpath.disabledAlgorithms}: weak keys such as RSA under 1024 bits, and signatures such as MD5.
     * Skipping the wrapper skipped that too, so {@link PinnedTrustManager} puts it back itself by running the
     * JDK's own PKIX validator over the leaf's issuer path in the served chain. The pin is what establishes
     * trust; the validator is there for the policy, which it takes from this JVM's {@code java.security},
     * including any local tightening. It applies it to every certificate on that path, the served root
     * included, with one exception: a chain served without its root ends at a certificate that can only be
     * trusted as the anchor, and that certificate's own signature is not checked. The validator also brings
     * three checks the wrapper did not make: an expired or not-yet-valid certificate is refused, a pinned
     * certificate issued by a CA has to be served with its chain, and a certificate carrying a critical
     * extension the JDK does not recognise is refused. Handing the pinned certificate to a {@code TrustManagerFactory} instead would look equivalent
     * and check nothing: {@code sun.security.validator.PKIXValidator} returns a chain whose first
     * certificate is already trusted without validating it.
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
        } catch (GeneralSecurityException e) {
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
     * JSSE does not add its own hostname check after this one, and so it applies the runtime's certificate
     * policy itself; see {@link #pinnedClient}. The socket and engine overloads are the ones JSSE actually
     * calls, and each applies the same checks as the plain one.
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
            try {
                checkPinAndPolicy(chain);
            } catch (RuntimeException e) {
                // Fail closed with the type JSSE expects. JSSE hands over a chain it has already parsed, so
                // nothing here should throw one; this is for the day something does.
                throw new CertificateException("cannot check the pool's certificate: " + e, e);
            }
        }

        private void checkPinAndPolicy(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0 || chain[0] == null) {
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
            checkAgainstRuntimePolicy(chain);
        }

        /**
         * Run the JDK's PKIX validator over the leaf's issuer path, for the runtime's algorithm policy and
         * validity dates.
         *
         * <p>The path is found by issuer rather than by position, starting from the pinned leaf, so a chain
         * served out of order is still checked and a certificate the leaf does not chain through decides
         * nothing. When the path ends at a self-issued certificate, that certificate is both the anchor and
         * part of the path, so its own signature, key and dates are checked as well. When it ends short of
         * one, because the server did not send its root, the last certificate sent can only be the anchor,
         * and its own signature is not checked; a leaf issued by a CA and served entirely alone cannot be
         * checked at all, and says so.
         */
        private static void checkAgainstRuntimePolicy(X509Certificate[] chain) throws CertificateException {
            List<X509Certificate> path = issuerPath(chain);
            X509Certificate top = path.get(path.size() - 1);
            boolean topSelfIssued = isSelfIssued(top);
            if (path.size() == 1 && !topSelfIssued) {
                throw new CertificateException("the pool's certificate matches the pinned fingerprint, but it was"
                        + " issued by " + top.getIssuerX500Principal().getName() + " and the pool served it"
                        + " without that chain, so this JVM's certificate policy cannot be checked against it."
                        + " Configure the pool to send its intermediate and root certificates.");
            }
            List<X509Certificate> validated = topSelfIssued ? path : path.subList(0, path.size() - 1);
            try {
                PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(top, null)));
                // No revocation: a pinned certificate is trusted by its bytes, and a self-signed one has no
                // issuer to publish a revocation list. The policy and the dates are what this is for.
                parameters.setRevocationEnabled(false);
                CertPathValidator.getInstance("PKIX")
                        .validate(CertificateFactory.getInstance("X.509").generateCertPath(validated), parameters);
            } catch (CertPathValidatorException e) {
                throw new CertificateException(
                        "the pool's certificate matches the pinned fingerprint but this JVM's certificate policy"
                                + " refuses it: " + e.getMessage(),
                        e);
            } catch (GeneralSecurityException e) {
                throw new CertificateException(
                        "cannot check the pool's certificate against this JVM's policy: " + e.getMessage(), e);
            }
        }

        /**
         * The pinned leaf, then its issuer, then that certificate's issuer, each looked up by subject among
         * the rest of the served chain, stopping at a self-issued certificate or at one whose issuer was not
         * sent. Each certificate is used at most once, so a loop in the served chain ends the walk.
         */
        private static List<X509Certificate> issuerPath(X509Certificate[] chain) {
            List<X509Certificate> path = new ArrayList<>();
            List<X509Certificate> rest = new ArrayList<>(Arrays.asList(chain).subList(1, chain.length));
            X509Certificate current = chain[0];
            path.add(current);
            while (!isSelfIssued(current)) {
                X509Certificate issuer = null;
                for (X509Certificate candidate : rest) {
                    if (candidate != null
                            && candidate.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                        issuer = candidate;
                        break;
                    }
                }
                if (issuer == null) {
                    break;
                }
                rest.remove(issuer);
                path.add(issuer);
                current = issuer;
            }
            return path;
        }

        private static boolean isSelfIssued(X509Certificate certificate) {
            return certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal());
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
