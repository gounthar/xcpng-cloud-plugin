package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the pinning is actually worth, measured against a real TLS handshake rather than against a fake
 * that agrees with us. A local {@link HttpsServer} serves a certificate generated for this run, so the
 * three cases that matter -- no pin, the right pin, the wrong pin -- are exercised end to end with no
 * pool involved and no private key in the repository.
 *
 * <p>The negative cases here are the ones worth distrusting, and both are built to discriminate. "A
 * self-signed certificate is rejected without a pin" would pass against a client that could not connect
 * at all, so the same server and the same request succeed in the pinned test directly below it. "A
 * different certificate is rejected" would pass against code containing no pinning whatsoever, which is
 * why the fingerprint comparison is the thing to mutate before believing any of this: breaking it must
 * take {@link #aDifferentCertificateIsRejected} down and leave the rest standing.
 */
class HttpTransportPinningTest {

    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";

    /** OpenSSL's placeholder subject, which is all a Xen Orchestra appliance's own certificate carries. */
    private static final String XO_DEFAULT_DN = "C=AU,ST=Some-State,O=Internet Widgits Pty Ltd";

    private HttpsServer server;
    private X509Certificate served;
    private String servedFingerprint;
    private String poolUrl;

    @BeforeEach
    void startServer() throws Exception {
        KeyPair keyPair = generateKeyPair();
        served = selfSigned(keyPair, "CN=127.0.0.1", "127.0.0.1");
        servedFingerprint = CertificateFingerprint.of(served);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext(keyPair, served)));
        server.createContext("/jsonrpc", exchange -> {
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        poolUrl = "https://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * With no fingerprint the JVM trust store decides, and it has never heard of this certificate. This is
     * the case the removed trust-all switch used to turn into a silent success.
     */
    @Test
    void aSelfSignedCertificateIsRejectedWithoutAPin() {
        HttpTransport transport = new HttpTransport(poolUrl, null);
        IOException failure = assertThrows(IOException.class, () -> transport.post(BODY));
        assertTrue(
                isTlsFailure(failure),
                "an untrusted certificate must fail as a TLS error, not as something else: " + failure);
    }

    /**
     * The same server and the same request, now with the certificate's fingerprint pinned, must succeed.
     * Without this the test above would pass just as happily against a transport that could not reach the
     * server at all, which would prove nothing about trust.
     */
    @Test
    void thePinnedCertificateIsAccepted() throws Exception {
        HttpTransport transport = new HttpTransport(poolUrl, servedFingerprint);
        assertEquals(BODY, transport.post(BODY), "a pinned certificate must complete the handshake");
    }

    /** The same fingerprint typed the way an operator pastes it: no colons, lower case. */
    @Test
    void thePinIsAcceptedInTheFormOperatorsPasteIt() throws Exception {
        String asPasted = servedFingerprint.replace(":", "").toLowerCase(java.util.Locale.ROOT);
        assertNotEquals(servedFingerprint, asPasted, "the fixture must really differ, or it proves nothing");
        HttpTransport transport = new HttpTransport(poolUrl, asPasted);
        assertEquals(BODY, transport.post(BODY));
    }

    /**
     * The case pinning exists for: the pool presents a certificate that is not the one confirmed by a
     * human. A replaced certificate and an interceptor are indistinguishable from here, and both must
     * stop the connection before the credential is sent.
     */
    @Test
    void aDifferentCertificateIsRejected() throws Exception {
        X509Certificate other = selfSigned(generateKeyPair(), "CN=127.0.0.1", "127.0.0.1");
        String otherFingerprint = CertificateFingerprint.of(other);
        assertNotEquals(servedFingerprint, otherFingerprint, "two generated certificates must differ");

        HttpTransport transport = new HttpTransport(poolUrl, otherFingerprint);
        IOException failure = assertThrows(IOException.class, () -> transport.post(BODY));
        assertTrue(isTlsFailure(failure), "a mismatched pin must fail the handshake: " + failure);
    }

    /**
     * A pinned certificate is accepted whether or not it names the host. This is the certificate a Xen
     * Orchestra appliance generates for itself: {@code openssl req -batch -new -x509} with no subject and
     * no extensions, so OpenSSL's placeholder DN, no CN and no subjectAltName. No address can ever match
     * it, and until #229 a pinned connection refused it on the hostname check after the pin had already
     * accepted it, which made every appliance still on its default certificate unreachable.
     */
    @Test
    void aPinnedCertificateThatNamesNoHostIsAccepted() throws Exception {
        KeyPair keyPair = generateKeyPair();
        X509Certificate xoDefault = selfSigned(keyPair, XO_DEFAULT_DN, null);

        withServer(keyPair, xoDefault, url -> {
            HttpTransport transport = new HttpTransport(url, CertificateFingerprint.of(xoDefault));
            assertEquals(BODY, transport.post(BODY), "the pin alone must identify the server");
        });
    }

    /**
     * The control for the test above. Accepting a certificate that names no host must not mean accepting
     * any such certificate: served one of them and pinned to another, the handshake still fails, so the
     * pin is doing the rejecting and not merely surviving a relaxed check.
     */
    @Test
    void aDifferentCertificateThatNamesNoHostIsStillRejected() throws Exception {
        KeyPair keyPair = generateKeyPair();
        X509Certificate served = selfSigned(keyPair, XO_DEFAULT_DN, null);
        X509Certificate pinned = selfSigned(generateKeyPair(), XO_DEFAULT_DN, null);
        assertNotEquals(CertificateFingerprint.of(served), CertificateFingerprint.of(pinned));

        withServer(keyPair, served, url -> {
            HttpTransport transport = new HttpTransport(url, CertificateFingerprint.of(pinned));
            IOException failure = assertThrows(IOException.class, () -> transport.post(BODY));
            assertTrue(isTlsFailure(failure), "a mismatched pin must fail the handshake: " + failure);
        });
    }

    /**
     * The pin does not waive the runtime's certificate policy. JSSE used to enforce {@code
     * jdk.certpath.disabledAlgorithms} in the wrapper that {@link #aPinnedCertificateThatNamesNoHostIsAccepted}
     * needed bypassed, so {@code PinnedTrustManager} applies it itself. A 768-bit RSA key is under the
     * default floor and can be factored, and the handshake only proves the server holds that key.
     */
    @Test
    void aPinnedCertificateWithAWeakKeyIsRejected() throws Exception {
        KeyPair weak = generateKeyPair(768);
        X509Certificate certificate = selfSigned(weak, "CN=127.0.0.1", "127.0.0.1");
        assertRefusedByPolicy(weak.getPrivate(), new Certificate[] {certificate}, "RSA 768");
    }

    /** The signature half of the same policy: MD5 is disabled by default, pinned or not. */
    @Test
    void aPinnedCertificateSignedWithADisabledAlgorithmIsRejected() throws Exception {
        KeyPair keyPair = generateKeyPair();
        X509Certificate certificate = issue(
                keyPair.getPublic(),
                "CN=127.0.0.1",
                "CN=127.0.0.1",
                keyPair.getPrivate(),
                "MD5withRSA",
                Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(1)),
                "127.0.0.1",
                false);
        assertRefusedByPolicy(keyPair.getPrivate(), new Certificate[] {certificate}, "MD5");
    }

    /**
     * A behaviour the plain trust manager never had: validity dates are checked, so an expired pinned
     * certificate is refused. Xen Orchestra regenerates its own certificate when it finds it expired, which
     * changes the fingerprint anyway, so a pin on an appliance breaks at renewal either way.
     */
    @Test
    void anExpiredPinnedCertificateIsRejected() throws Exception {
        KeyPair keyPair = generateKeyPair();
        X509Certificate certificate = issue(
                keyPair.getPublic(),
                "CN=127.0.0.1",
                "CN=127.0.0.1",
                keyPair.getPrivate(),
                "SHA256withRSA",
                Instant.now().minus(Duration.ofDays(10)),
                Instant.now().minus(Duration.ofDays(8)),
                "127.0.0.1",
                false);
        assertRefusedByPolicy(keyPair.getPrivate(), new Certificate[] {certificate}, "NotAfter");
    }

    /**
     * A pinned leaf issued by a CA is checked up the chain the server serves, anchored at its top. This
     * is the control for the test below: the same leaf, pinned the same way, with its chain.
     */
    @Test
    void aPinnedCertificateIssuedByACaNeedsItsChain() throws Exception {
        KeyPair caKeys = generateKeyPair();
        X509Certificate ca = issue(
                caKeys.getPublic(),
                "CN=Lab CA",
                "CN=Lab CA",
                caKeys.getPrivate(),
                "SHA256withRSA",
                Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(10)),
                null,
                true);
        KeyPair leafKeys = generateKeyPair();
        X509Certificate leaf = issue(
                leafKeys.getPublic(),
                "CN=127.0.0.1",
                "CN=Lab CA",
                caKeys.getPrivate(),
                "SHA256withRSA",
                Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(5)),
                "127.0.0.1",
                false);

        withServer(leafKeys.getPrivate(), new Certificate[] {leaf, ca}, url -> {
            HttpTransport transport = new HttpTransport(url, CertificateFingerprint.of(leaf));
            assertEquals(BODY, transport.post(BODY), "a CA-issued pinned leaf served with its chain must connect");
        });

        // The cost, on the same leaf: served alone, its signature cannot be checked, and the refusal says why.
        assertRefusedByPolicy(leafKeys.getPrivate(), new Certificate[] {leaf}, "without that chain");
    }

    /**
     * The policy reaches past a single certificate. Served with a root appended, a weak leaf is no longer
     * the chain's last element, and a check that only looked at lone certificates would let it through.
     */
    @Test
    void aWeakPinnedLeafIsRejectedEvenWithItsRootAppended() throws Exception {
        CertificateAuthority root = CertificateAuthority.root("CN=Lab CA", generateKeyPair(), "SHA256withRSA");
        KeyPair weak = generateKeyPair(768);
        X509Certificate leaf = root.issueLeaf(weak.getPublic());
        assertRefusedByPolicy(weak.getPrivate(), new Certificate[] {leaf, root.certificate}, "768");
    }

    /** The issuer's key is under the same policy: a leaf signed by a 768-bit root is refused. */
    @Test
    void aPinnedLeafIssuedByAWeakRootIsRejected() throws Exception {
        CertificateAuthority root = CertificateAuthority.root("CN=Lab CA", generateKeyPair(768), "SHA256withRSA");
        KeyPair leafKeys = generateKeyPair();
        X509Certificate leaf = root.issueLeaf(leafKeys.getPublic());
        assertRefusedByPolicy(leafKeys.getPrivate(), new Certificate[] {leaf, root.certificate}, "768");
    }

    /**
     * A served root is checked too, not only trusted as an anchor. Anchoring at the last certificate
     * without validating it left the root's own signature unexamined, so an MD5 self-signature passed
     * where the plain trust manager refused it.
     */
    @Test
    void aRootThatSignedItselfWithADisabledAlgorithmIsRejected() throws Exception {
        CertificateAuthority root = CertificateAuthority.root("CN=Lab CA", generateKeyPair(), "MD5withRSA");
        KeyPair leafKeys = generateKeyPair();
        X509Certificate leaf = root.issueLeaf(leafKeys.getPublic());
        assertRefusedByPolicy(leafKeys.getPrivate(), new Certificate[] {leaf, root.certificate}, "MD5");
    }

    /**
     * Certificates the leaf does not chain through do not decide anything. A self-signed pinned leaf
     * followed by an unrelated certificate was accepted before the policy check existed, and still is.
     */
    @Test
    void anUnrelatedCertificateAfterAPinnedSelfSignedLeafIsIgnored() throws Exception {
        KeyPair keyPair = generateKeyPair();
        X509Certificate leaf = selfSigned(keyPair, "CN=127.0.0.1", "127.0.0.1");
        X509Certificate unrelated =
                CertificateAuthority.root("CN=Unrelated", generateKeyPair(), "SHA256withRSA").certificate;

        withServer(keyPair.getPrivate(), new Certificate[] {leaf, unrelated}, url -> {
            HttpTransport transport = new HttpTransport(url, CertificateFingerprint.of(leaf));
            assertEquals(BODY, transport.post(BODY), "an unrelated extra certificate must not refuse the pin");
        });
    }

    /** Servers do send chains out of order; the path is found by issuer, not by position. */
    @Test
    void aChainServedOutOfOrderIsAccepted() throws Exception {
        CertificateAuthority root = CertificateAuthority.root("CN=Lab CA", generateKeyPair(), "SHA256withRSA");
        CertificateAuthority intermediate = root.intermediate("CN=Lab Intermediate", generateKeyPair());
        KeyPair leafKeys = generateKeyPair();
        X509Certificate leaf = intermediate.issueLeaf(leafKeys.getPublic());

        withServer(leafKeys.getPrivate(), new Certificate[] {leaf, root.certificate, intermediate.certificate}, url -> {
            HttpTransport transport = new HttpTransport(url, CertificateFingerprint.of(leaf));
            assertEquals(BODY, transport.post(BODY), "a chain served out of order must still be checked and accepted");
        });
    }

    /** A CA for one test: its own certificate and the key it signs with. */
    private static final class CertificateAuthority {
        final X509Certificate certificate;
        final KeyPair keyPair;

        private CertificateAuthority(X509Certificate certificate, KeyPair keyPair) {
            this.certificate = certificate;
            this.keyPair = keyPair;
        }

        static CertificateAuthority root(String dn, KeyPair keyPair, String selfSignature) throws Exception {
            return new CertificateAuthority(
                    issue(
                            keyPair.getPublic(),
                            dn,
                            dn,
                            keyPair.getPrivate(),
                            selfSignature,
                            Instant.now().minus(Duration.ofDays(1)),
                            Instant.now().plus(Duration.ofDays(10)),
                            null,
                            true),
                    keyPair);
        }

        CertificateAuthority intermediate(String dn, KeyPair keys) throws Exception {
            return new CertificateAuthority(
                    issue(
                            keys.getPublic(),
                            dn,
                            certificate.getSubjectX500Principal().getName(),
                            keyPair.getPrivate(),
                            "SHA256withRSA",
                            Instant.now().minus(Duration.ofDays(1)),
                            Instant.now().plus(Duration.ofDays(10)),
                            null,
                            true),
                    keys);
        }

        X509Certificate issueLeaf(PublicKey subjectKey) throws Exception {
            return issue(
                    subjectKey,
                    "CN=127.0.0.1",
                    certificate.getSubjectX500Principal().getName(),
                    keyPair.getPrivate(),
                    "SHA256withRSA",
                    Instant.now().minus(Duration.ofDays(1)),
                    Instant.now().plus(Duration.ofDays(5)),
                    "127.0.0.1",
                    false);
        }
    }

    /**
     * Pinned to exactly what is served, so only the certificate policy can refuse it, and the refusal must
     * be the plugin's own, naming {@code expected}: a handshake failing for any other reason proves nothing.
     */
    private static void assertRefusedByPolicy(PrivateKey key, Certificate[] chain, String expected) throws Exception {
        String pin = CertificateFingerprint.of((X509Certificate) chain[0]);
        withServer(key, chain, url -> {
            HttpTransport transport = new HttpTransport(url, pin);
            IOException failure = assertThrows(IOException.class, () -> transport.post(BODY));
            assertTrue(isTlsFailure(failure), "the refusal must be a TLS failure: " + failure);
            String messages = messages(failure);
            assertTrue(
                    messages.contains("matches the pinned fingerprint"),
                    "refused by the pin check instead: " + messages);
            assertTrue(messages.contains(expected), "expected a refusal naming " + expected + ": " + messages);
        });
    }

    /**
     * Reading the fingerprint off a live host is what the operator is shown before they confirm it. It
     * must report exactly what the server serves, and it must do so without completing a handshake --
     * asserted here by the value alone, since a wrong value would make the pinning tests above unusable.
     */
    @Test
    void fetchReportsTheCertificateTheHostPresents() throws Exception {
        assertEquals(servedFingerprint, CertificateFingerprint.fetch(poolUrl));
    }

    /** A host that is not listening is a reachability failure, not an empty fingerprint. */
    @Test
    void fetchFailsRatherThanInventingAFingerprint() {
        server.stop(0);
        assertThrows(IOException.class, () -> CertificateFingerprint.fetch(poolUrl));
    }

    /** Run {@code body} against a second server presenting {@code certificate}, then stop it. */
    private static void withServer(KeyPair keyPair, X509Certificate certificate, ServerBody body) throws Exception {
        withServer(keyPair.getPrivate(), new Certificate[] {certificate}, body);
    }

    /** As above, serving {@code chain} exactly as given, leaf first. */
    private static void withServer(PrivateKey key, Certificate[] chain, ServerBody body) throws Exception {
        HttpsServer other = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.setHttpsConfigurator(new HttpsConfigurator(serverContext(key, chain)));
        other.createContext("/jsonrpc", exchange -> {
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        other.start();
        try {
            body.run("https://127.0.0.1:" + other.getAddress().getPort());
        } finally {
            other.stop(0);
        }
    }

    @FunctionalInterface
    private interface ServerBody {
        void run(String url) throws Exception;
    }

    private static String messages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            all.append(cause.getMessage()).append(" | ");
        }
        return all.toString();
    }

    private static boolean isTlsFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException) {
                return true;
            }
        }
        return false;
    }

    private static KeyPair generateKeyPair() throws Exception {
        return generateKeyPair(2048);
    }

    private static KeyPair generateKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    /**
     * A throwaway self-signed certificate, valid around now, for one test run.
     *
     * @param ipSan an address to place in the subjectAltName, or null for a certificate that names no
     *     address at all -- which is what {@link #aPinnedCertificateThatNamesNoHostIsAccepted} needs.
     */
    private static X509Certificate selfSigned(KeyPair keyPair, String dn, String ipSan) throws Exception {
        Instant now = Instant.now();
        return issue(
                keyPair.getPublic(),
                dn,
                dn,
                keyPair.getPrivate(),
                "SHA256withRSA",
                now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(1)),
                ipSan,
                false);
    }

    /**
     * A throwaway certificate for {@code subjectKey}, signed by {@code issuerKey}.
     *
     * @param ipSan an address to place in the subjectAltName, or null for none.
     * @param ca whether to mark it as a CA, which a chain's anchor needs.
     */
    private static X509Certificate issue(
            PublicKey subjectKey,
            String subjectDn,
            String issuerDn,
            PrivateKey issuerKey,
            String signatureAlgorithm,
            Instant notBefore,
            Instant notAfter,
            String ipSan,
            boolean ca)
            throws Exception {
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuerDn),
                // Serials must differ between the certificates a run generates, and the clock alone is too
                // coarse: several are built inside the same millisecond.
                new BigInteger(64, new java.security.SecureRandom()),
                Date.from(notBefore),
                Date.from(notAfter),
                new X500Name(subjectDn),
                subjectKey);
        if (ipSan != null) {
            builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(GeneralName.iPAddress, ipSan)));
        }
        if (ca) {
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        }
        ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm).build(issuerKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static SSLContext serverContext(KeyPair keyPair, X509Certificate certificate) throws Exception {
        return serverContext(keyPair.getPrivate(), new Certificate[] {certificate});
    }

    /**
     * A server that presents {@code chain} exactly as given. A keystore will not hold a chain that does not
     * link in order, which is precisely the shape some of these tests need to serve, so the key manager
     * answers with the chain directly instead.
     */
    private static SSLContext serverContext(PrivateKey key, Certificate[] chain) throws Exception {
        X509Certificate[] served = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            served[i] = (X509Certificate) chain[i];
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {new FixedKeyManager(key, served)}, null, null);
        return context;
    }

    private static final class FixedKeyManager extends X509ExtendedKeyManager {
        private static final String ALIAS = "pool";
        private final PrivateKey key;
        private final X509Certificate[] chain;

        FixedKeyManager(PrivateKey key, X509Certificate[] chain) {
            this.key = key;
            this.chain = chain;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return keyType.equals(key.getAlgorithm()) ? ALIAS : null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return keyType.equals(key.getAlgorithm()) ? ALIAS : null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return keyType.equals(key.getAlgorithm()) ? new String[] {ALIAS} : null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return chain.clone();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }
}
