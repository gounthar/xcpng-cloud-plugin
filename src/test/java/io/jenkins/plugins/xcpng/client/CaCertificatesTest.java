package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What a pasted CA value is read as (#172). The trust decision is {@code HttpRestTransportPinningTest}'s, over
 * a real handshake; this is the parsing in front of it, and above all what it refuses.
 */
class CaCertificatesTest {

    private static X509Certificate rootCa;
    private static X509Certificate otherCa;
    private static X509Certificate leaf;

    @BeforeAll
    static void certificates() throws Exception {
        KeyPair rootKeys = keyPair();
        rootCa = certificate("CN=Org CA", "CN=Org CA", rootKeys, rootKeys, true);
        KeyPair otherKeys = keyPair();
        otherCa = certificate("CN=Other CA", "CN=Other CA", otherKeys, otherKeys, true);
        leaf = certificate("CN=xo.example.test", "CN=Org CA", keyPair(), rootKeys, false);
    }

    @Test
    void oneCaIsRead() throws Exception {
        assertEquals(List.of(rootCa), CaCertificates.parse(pem(rootCa)));
    }

    /**
     * Two authorities, with the {@code subject=} and {@code issuer=} lines {@code openssl x509} prints around
     * each, and Windows line endings: what a paste from a terminal on another machine looks like.
     */
    @Test
    void severalCasAreReadThroughTheTextAroundThem() throws Exception {
        String pasted =
                "subject=CN = Org CA\r\nissuer=CN = Org CA\r\n" + pem(rootCa).replace("\n", "\r\n")
                        + "\r\nsubject=CN = Other CA\r\n" + pem(otherCa).replace("\n", "\r\n");
        assertEquals(List.of(rootCa, otherCa), CaCertificates.parse(pasted));
    }

    /**
     * A server certificate is refused, by name, and the message points at the fingerprint field. Trusted as an
     * anchor it would be accepted on its bytes with the policy and expiry checks skipped.
     */
    @Test
    void aCertificateThatIsNotACaIsRefused() throws Exception {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> CaCertificates.parse(pem(rootCa) + pem(leaf)));
        assertTrue(e.getMessage().contains("Certificate 2"), e.getMessage());
        assertTrue(e.getMessage().contains("xo.example.test"), e.getMessage());
        assertTrue(e.getMessage().contains("Certificate fingerprint"), e.getMessage());
    }

    /** A private key in the paste is refused outright, even alongside a valid CA, because it would be saved. */
    @Test
    void aPasteContainingAPrivateKeyIsRefused() throws Exception {
        String pasted = pem(rootCa) + "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg\n-----END PRIVATE KEY-----\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CaCertificates.parse(pasted));
        assertTrue(e.getMessage().contains("private key"), e.getMessage());

        String rsa = "-----BEGIN RSA PRIVATE KEY-----\nMIIEvQIBADANBg\n-----END RSA PRIVATE KEY-----\n";
        assertThrows(IllegalArgumentException.class, () -> CaCertificates.parse(rsa));
    }

    @Test
    void textWithNoCertificateIsRefused() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> CaCertificates.parse("just some text"));
        assertTrue(e.getMessage().contains("No PEM certificate found"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CaCertificates.parse("   \n "));
        assertThrows(IllegalArgumentException.class, () -> CaCertificates.parse(null));
    }

    @Test
    void aBlockThatDoesNotDecodeIsRefusedByPosition() throws Exception {
        String broken = pem(rootCa) + "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CaCertificates.parse(broken));
        assertTrue(e.getMessage().contains("Certificate 2"), e.getMessage());

        String notBase64 = "-----BEGIN CERTIFICATE-----\n%%%%\n-----END CERTIFICATE-----";
        assertThrows(IllegalArgumentException.class, () -> CaCertificates.parse(notBase64));
    }

    @Test
    void normalizeUnifiesLineEndingsAndDropsBlank() throws Exception {
        assertEquals("a\nb\nc", CaCertificates.normalize("  a\r\nb\rc\n\n"));
        assertNull(CaCertificates.normalize(" \r\n "));
        assertNull(CaCertificates.normalize(null));
        // Kept even when it does not parse: dropping it would widen trust to the JVM store.
        assertEquals("garbage", CaCertificates.normalize("garbage"));
    }

    static String pem(X509Certificate certificate) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(
            String subject, String issuer, KeyPair subjectKeys, KeyPair issuerKeys, boolean ca) throws Exception {
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuer),
                new BigInteger(64, new SecureRandom()),
                Date.from(Instant.now().minus(Duration.ofDays(1))),
                Date.from(Instant.now().plus(Duration.ofDays(10))),
                new X500Name(subject),
                subjectKeys.getPublic());
        if (ca) {
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        }
        return new JcaX509CertificateConverter()
                .getCertificate(
                        builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.getPrivate())));
    }
}
