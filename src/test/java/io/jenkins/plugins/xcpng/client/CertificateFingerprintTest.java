package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Parsing what an operator pastes. The point of normalising at all is that the value persisted in
 * {@code config.xml} and the value compared on every handshake must be the same string, whatever
 * punctuation and case it arrived in -- a fingerprint copied from {@code openssl} and one copied from a
 * browser's certificate viewer differ in both, and neither operator is wrong.
 */
class CertificateFingerprintTest {

    private static final String CANONICAL =
            "C1:40:4D:63:89:50:C4:C2:21:93:FD:59:64:72:A3:62:9D:99:C9:4C:45:31:F2:C4:07:84:28:8A:51:EB:AA:06";

    @Test
    void aCanonicalFingerprintIsUnchanged() {
        assertEquals(CANONICAL, CertificateFingerprint.normalize(CANONICAL));
    }

    @Test
    void colonsAreOptionalAndCaseIsIgnored() {
        String stripped = CANONICAL.replace(":", "").toLowerCase(Locale.ROOT);
        assertEquals(CANONICAL, CertificateFingerprint.normalize(stripped));
    }

    /** Pasting out of a terminal or a certificate viewer routinely brings whitespace along. */
    @Test
    void surroundingAndEmbeddedWhitespaceIsIgnored() {
        assertEquals(CANONICAL, CertificateFingerprint.normalize("  " + CANONICAL.replace(":", " ") + "\n"));
    }

    /**
     * A truncated paste is the realistic mistake, and it must not be accepted: a short value would be
     * persisted, compared against every certificate, and match none of them, which looks like a network
     * fault rather than a typo.
     */
    @Test
    void aTruncatedFingerprintIsRejected() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> CertificateFingerprint.normalize(CANONICAL.substring(0, 40)));
        assertTrue(e.getMessage().contains("64"), e.getMessage());
    }

    /** A SHA-1 fingerprint is the wrong length, and is what someone reaching for an older habit pastes. */
    @Test
    void aSha1FingerprintIsRejected() {
        assertFalse(CertificateFingerprint.isValid("DA:39:A3:EE:5E:6B:4B:0D:32:55:BF:EF:95:60:18:90:AF:D8:07:09"));
    }

    /** Non-hex characters are named individually, because "invalid" alone does not locate a typo. */
    @Test
    void aNonHexCharacterIsNamed() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> CertificateFingerprint.normalize(CANONICAL.replace("C1", "GG")));
        assertTrue(e.getMessage().contains("'G'"), e.getMessage());
    }

    @Test
    void blankIsRejectedRatherThanTreatedAsAbsent() {
        // Absent is the caller's decision to make -- normalize's job is to parse, and "" parses to nothing.
        assertThrows(IllegalArgumentException.class, () -> CertificateFingerprint.normalize("   "));
        assertFalse(CertificateFingerprint.isValid(null));
    }
}
