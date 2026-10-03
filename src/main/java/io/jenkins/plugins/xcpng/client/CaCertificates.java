package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The CA certificates an operator supplies for a cloud (#172): how a pasted PEM is normalised and how it is
 * read into the trust anchors {@link TrustedHttpClients} verifies against.
 *
 * <p>The blocks are found by their armour and decoded one at a time rather than handed to {@link
 * CertificateFactory#generateCertificates} whole. That method's tolerance of text around and between
 * blocks is an implementation detail; a paste routinely carries some, such as the {@code subject=} and
 * {@code issuer=} lines {@code openssl x509 -text} prints, and finding the blocks here makes what is
 * accepted a property of this class.
 */
public final class CaCertificates {

    private static final Pattern BLOCK =
            Pattern.compile("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL);

    private CaCertificates() {}

    /**
     * The value as it should be persisted: line endings made {@code \n} and surrounding whitespace dropped,
     * or null when nothing is left. Not parsed, so a malformed value survives normalisation; see {@code
     * XcpngCloud#setCaCertificates} for why it is kept rather than dropped.
     */
    @CheckForNull
    public static String normalize(@CheckForNull String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Read every certificate in {@code pem}, each of which must be a CA.
     *
     * <p>A certificate that is not a CA is refused rather than trusted, and the reason is the trust manager,
     * not tidiness. The JDK's PKIX validator accepts a served certificate that is itself one of the anchors
     * without validating anything about it, so a leaf pasted here would be trusted by its bytes alone and
     * with the expiry and policy checks skipped. That is a pin with fewer checks than the real one; the
     * fingerprint field is where a single certificate belongs.
     *
     * @throws IllegalArgumentException naming what is wrong: no certificate found, a block that does not
     *     decode, a private key in the paste, or a certificate that is not a CA. The message is shown to the
     *     operator as it is.
     */
    @NonNull
    public static List<X509Certificate> parse(@CheckForNull String pem) {
        String text = normalize(pem);
        if (text == null) {
            throw new IllegalArgumentException("No CA certificate given.");
        }
        // Checked before anything else, and refused rather than skipped: whatever is pasted here is saved
        // to config.xml in the clear, and a private key that reaches that file has been disclosed to anyone
        // who can read the controller's disk or its backups, whether or not this class then ignores it.
        if (text.contains("PRIVATE KEY-----")) {
            throw new IllegalArgumentException("This contains a private key. Paste only the CA's certificate, the"
                    + " block between BEGIN CERTIFICATE and END CERTIFICATE; the key must never leave the CA.");
        }
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IllegalStateException("this JVM cannot read X.509 certificates: " + e.getMessage(), e);
        }
        List<X509Certificate> certificates = new ArrayList<>();
        Matcher block = BLOCK.matcher(text);
        while (block.find()) {
            int index = certificates.size() + 1;
            X509Certificate certificate = decode(factory, block.group(1), index);
            if (certificate.getBasicConstraints() < 0) {
                throw new IllegalArgumentException("Certificate " + index + " ("
                        + certificate.getSubjectX500Principal().getName() + ") is not a CA certificate. Paste the"
                        + " certificate of the authority that issued the appliance's certificate; to trust one"
                        + " certificate exactly, use Certificate fingerprint instead.");
            }
            certificates.add(certificate);
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException(
                    "No PEM certificate found. Expected a block starting with -----BEGIN CERTIFICATE-----.");
        }
        return certificates;
    }

    private static X509Certificate decode(CertificateFactory factory, String body, int index) {
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Certificate " + index + " is not valid base64: " + e.getMessage(), e);
        }
        try {
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException | ClassCastException e) {
            throw new IllegalArgumentException(
                    "Certificate " + index + " is not a readable X.509 certificate: " + e.getMessage(), e);
        }
    }
}
