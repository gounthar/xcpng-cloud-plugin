package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The client caches stay bounded however many fingerprints or CA sets an admin cycles through (#322), and
 * still share a client between clouds that trust the same thing.
 *
 * <p>The caches are static and other tests in this JVM fill them too, so nothing here assumes they start
 * empty: the bound is checked as an upper limit, and the eviction tests first fill the cache with entries of
 * their own.
 */
class TrustedHttpClientsCacheTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @Test
    void theSamePinSharesOneClient() {
        String fingerprint = randomFingerprint();
        assertSame(pinned(fingerprint), pinned(fingerprint));
    }

    @Test
    void distinctPinsPastTheLimitDoNotGrowTheCache() {
        for (int i = 0; i < TrustedHttpClients.CACHE_LIMIT * 3; i++) {
            pinned(randomFingerprint());
        }
        assertTrue(
                TrustedHttpClients.pinnedClientCount() <= TrustedHttpClients.CACHE_LIMIT,
                "pinned clients cached: " + TrustedHttpClients.pinnedClientCount());
    }

    @Test
    void distinctCaSetsPastTheLimitDoNotGrowTheCache() throws Exception {
        for (int i = 0; i < TrustedHttpClients.CACHE_LIMIT * 3; i++) {
            anchored(randomCaPem());
        }
        assertTrue(
                TrustedHttpClients.anchoredClientCount() <= TrustedHttpClients.CACHE_LIMIT,
                "CA-anchored clients cached: " + TrustedHttpClients.anchoredClientCount());
    }

    /**
     * The pin used last is the one kept. The second half is the control: without that use, the same pin is
     * evicted, so the first half is not passing because nothing is ever evicted.
     */
    @Test
    void aPinInUseOutlivesOnesThatAreNot() {
        String kept = randomFingerprint();
        HttpClient keptClient = pinned(kept);
        for (int i = 0; i < TrustedHttpClients.CACHE_LIMIT - 1; i++) {
            pinned(randomFingerprint());
        }
        pinned(kept);
        pinned(randomFingerprint());
        assertSame(keptClient, pinned(kept), "a pin used since the cache filled was evicted");

        String unused = randomFingerprint();
        HttpClient unusedClient = pinned(unused);
        for (int i = 0; i < TrustedHttpClients.CACHE_LIMIT; i++) {
            pinned(randomFingerprint());
        }
        assertNotSame(unusedClient, pinned(unused), "a pin nobody used was never evicted");
    }

    private static HttpClient pinned(String fingerprint) {
        return TrustedHttpClients.forTrust(PoolTrust.pinned(fingerprint));
    }

    private static HttpClient anchored(String caPem) {
        return TrustedHttpClients.forTrust(PoolTrust.anchoredAt(caPem));
    }

    private static String randomFingerprint() {
        byte[] digest = new byte[32];
        RANDOM.nextBytes(digest);
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
    }

    /** A self-signed CA with a fresh subject, so each one has its own fingerprint. */
    private static String randomCaPem() throws Exception {
        String dn = "CN=ca-" + Long.toHexString(RANDOM.nextLong());
        return CaCertificatesTest.pem(
                CaCertificatesTest.certificate(dn, dn, keys, keys, true, KeyUsage.keyCertSign | KeyUsage.cRLSign));
    }
}
