package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The SHA-256 fingerprint of a pool's TLS certificate: how it is computed, how an operator's typing of
 * one is normalised, and how the one a pool currently presents is read off the wire.
 *
 * <p>This is the identity the plugin pins to. XCP-ng ships a self-signed certificate, so the ordinary
 * chain check cannot succeed against a stock pool; pinning is what replaces it, and it is strictly
 * stronger than the trust-all switch it replaced, because a certificate that is later swapped fails
 * loudly instead of being accepted in silence.
 *
 * <p>The format is the one {@code openssl x509 -fingerprint -sha256} prints — 32 upper-case hex pairs
 * joined by colons — because that is what an operator will have in their paste buffer. {@link #normalize}
 * accepts it with or without the colons and in either case, so a fingerprint copied out of a browser's
 * certificate viewer is not rejected on punctuation.
 */
public final class CertificateFingerprint {

    /** Bytes in a SHA-256 digest, and therefore hex pairs in a fingerprint. */
    private static final int SHA256_BYTES = 32;

    private static final int PROBE_TIMEOUT_MILLIS = 10_000;

    private CertificateFingerprint() {}

    /**
     * The canonical fingerprint of a certificate: SHA-256 over its DER encoding.
     *
     * <p>Over the encoded certificate rather than over the public key, which is the other common choice.
     * Certificate pinning is the stricter of the two: re-issuing with the same key still changes the
     * fingerprint and still has to be re-confirmed by a human. On a pool whose certificate is replaced
     * perhaps once in its life, that is the right side to err on.
     */
    @NonNull
    public static String of(@NonNull X509Certificate certificate) {
        byte[] der;
        try {
            der = certificate.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new HypervisorException("cannot read the certificate's encoded form: " + e.getMessage(), e);
        }
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every conforming JRE ships SHA-256; if this one does not, nothing below can be trusted.
            throw new HypervisorException("SHA-256 is unavailable in this JRE", e);
        }
        return format(sha256.digest(der));
    }

    /**
     * True when {@code raw} is a syntactically valid SHA-256 fingerprint. Used by the form validator,
     * which must answer without throwing; {@link #normalize} is the throwing form for callers that want
     * the canonical value.
     */
    public static boolean isValid(String raw) {
        try {
            normalize(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Canonicalise an operator-supplied fingerprint, so what is persisted and what is compared on every
     * handshake are the same string regardless of how it was typed.
     *
     * @param raw a SHA-256 fingerprint, with or without colon separators, in either case, possibly
     *     surrounded or interrupted by whitespace.
     * @return the fingerprint as 32 upper-case hex pairs joined by colons.
     * @throws IllegalArgumentException if it is not 64 hex digits once punctuation is removed. The
     *     message names what was wrong, because it is shown to the operator by the field validator.
     */
    @NonNull
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A certificate fingerprint is required.");
        }
        StringBuilder hex = new StringBuilder(SHA256_BYTES * 2);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ':' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            // Reject anything that is not a hex digit here rather than letting a typo through to a
            // length check, so "not hexadecimal" and "wrong length" are distinguishable to the reader.
            if (Character.digit(c, 16) < 0) {
                throw new IllegalArgumentException(
                        "A SHA-256 fingerprint contains only hexadecimal digits and colons; found '" + c + "'.");
            }
            hex.append(Character.toUpperCase(c));
        }
        if (hex.length() != SHA256_BYTES * 2) {
            throw new IllegalArgumentException("A SHA-256 fingerprint is " + (SHA256_BYTES * 2)
                    + " hexadecimal digits; this one has " + hex.length() + ".");
        }
        StringBuilder out = new StringBuilder(SHA256_BYTES * 3 - 1);
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }

    /**
     * Read the certificate a pool is presenting right now, without ever trusting it.
     *
     * <p>This is the half of trust-on-first-use that a human completes: the operator is shown a
     * fingerprint and decides whether it is their pool. The trust manager used here records the leaf
     * certificate and then <em>refuses</em> it, so the handshake never completes and not one byte of the
     * XAPI credential is offered to a peer nobody has vouched for. That is the difference between this
     * and the trust-all context it replaced, which accepted every certificate and then talked to it.
     *
     * @param poolUrl the pool's base URL; its host and port are used, the path is ignored.
     * @return the canonical fingerprint of the leaf certificate the host presented.
     * @throws IOException if the host cannot be reached, or if the connection failed before any
     *     certificate was presented — in which case there is nothing to show and nothing to pin.
     */
    @NonNull
    public static String fetch(@NonNull String poolUrl) throws IOException {
        URI uri;
        try {
            uri = new URI(poolUrl.trim());
        } catch (URISyntaxException e) {
            throw new IOException("Not a valid pool URL: " + e.getMessage(), e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("The pool URL has no host to connect to.");
        }
        int port = uri.getPort() == -1 ? 443 : uri.getPort();

        AtomicReference<X509Certificate> presented = new AtomicReference<>();
        SSLContext ctx = recordingContext(presented);

        // A plain socket connected first, so the connect phase gets its own timeout; createSocket(host,
        // port) would block on a dead host for as long as the OS wants to wait.
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS);
            plain.setSoTimeout(PROBE_TIMEOUT_MILLIS);
            try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket(plain, host, port, false)) {
                socket.startHandshake();
            } catch (IOException expected) {
                // The refusal above is the normal outcome and arrives here as a handshake failure. Whether
                // it was ours is decided by what got recorded, not by the exception: a genuine transport
                // failure records nothing and is rethrown below.
                if (presented.get() == null) {
                    throw expected;
                }
            }
        }
        X509Certificate leaf = presented.get();
        if (leaf == null) {
            throw new IOException("The host closed the connection before presenting a certificate.");
        }
        return of(leaf);
    }

    /**
     * An SSL context whose trust manager records the server's leaf certificate and then refuses it.
     *
     * <p>The scan flags every {@code SSLContext#init}, because that call is how TLS verification is
     * normally switched off; it does not read the trust manager it is handed. This one does the
     * opposite of weakening anything. The manager below accepts nothing at all: it records the leaf
     * and then throws unconditionally, so no handshake it takes part in can ever complete. Suppressed
     * rather than dismissed through the API so the reasoning sits beside the code. See #142.
     */
    @SuppressWarnings("lgtm[jenkins/unsafe-calls]") // Records the certificate, then refuses it. Accepts nothing.
    private static SSLContext recordingContext(AtomicReference<X509Certificate> sink) throws IOException {
        X509TrustManager recorder = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                // This context is only ever a client. Reached only if it were misused as a server.
                throw new CertificateException("this trust manager never authenticates a client");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain != null && chain.length > 0) {
                    sink.set(chain[0]);
                }
                // Refuse unconditionally. The caller wanted to look at the certificate, not to use it.
                throw new CertificateException("certificate recorded for inspection; connection refused by design");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {recorder}, new SecureRandom());
            return ctx;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("cannot build an SSL context to inspect the certificate: " + e.getMessage(), e);
        }
    }

    /** Digest bytes to the colon-separated upper-case hex the rest of the world prints. */
    private static String format(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 3 - 1);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(String.format(Locale.ROOT, "%02X", digest[i]));
        }
        return out.toString();
    }
}
