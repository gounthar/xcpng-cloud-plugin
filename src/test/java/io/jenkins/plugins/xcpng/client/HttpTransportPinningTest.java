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
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
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

    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();
    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";

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
     * Pinning does not replace hostname verification, it stacks on top of it. {@code HttpClient} forces
     * endpoint identification on regardless of what a caller asks for, so a certificate that matches the
     * pin but does not name the host is still refused. Worth a test of its own because the opposite is the
     * intuitive guess, and because the transport's own comment is the only other place it is written down.
     */
    @Test
    void aCertificateThatDoesNotNameTheHostIsRejected() throws Exception {
        KeyPair keyPair = generateKeyPair();
        X509Certificate anonymous = selfSigned(keyPair, "CN=somewhere-else", null);

        HttpsServer other = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.setHttpsConfigurator(new HttpsConfigurator(serverContext(keyPair, anonymous)));
        other.createContext("/jsonrpc", exchange -> {
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        other.start();
        try {
            String url = "https://127.0.0.1:" + other.getAddress().getPort();
            // Pinned to exactly what this server serves, so only the missing hostname can fail it.
            HttpTransport transport = new HttpTransport(url, CertificateFingerprint.of(anonymous));
            IOException failure = assertThrows(IOException.class, () -> transport.post(BODY));
            assertTrue(isTlsFailure(failure), "a certificate that names no host must fail: " + failure);
        } finally {
            other.stop(0);
        }
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

    private static boolean isTlsFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException) {
                return true;
            }
        }
        return false;
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * A throwaway self-signed certificate, valid around now, for one test run.
     *
     * @param ipSan an address to place in the subjectAltName, or null for a certificate that names no
     *     address at all -- which is what {@link #aCertificateThatDoesNotNameTheHostIsRejected} needs.
     */
    private static X509Certificate selfSigned(KeyPair keyPair, String dn, String ipSan) throws Exception {
        X500Name subject = new X500Name(dn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                // Serials must differ between the two certificates a run generates, and the clock alone is
                // too coarse: both are built inside the same millisecond.
                new BigInteger(64, new java.security.SecureRandom()),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(1))),
                subject,
                keyPair.getPublic());
        if (ipSan != null) {
            builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(GeneralName.iPAddress, ipSan)));
        }
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static SSLContext serverContext(KeyPair keyPair, X509Certificate certificate) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("pool", keyPair.getPrivate(), KEYSTORE_PASSWORD, new Certificate[] {certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, KEYSTORE_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }
}
