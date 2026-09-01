package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The only part of the client that touches the network: POST a JSON-RPC body to the pool's
 * {@code /jsonrpc} endpoint and return the response body. Kept separate from {@link XapiClient} so the
 * verb and error-envelope logic can be tested against recorded JSON fixtures without a pool, and so the
 * HTTP and TLS concerns live in one small place.
 *
 * <p>There are exactly two TLS modes and neither of them is "accept anything". With no pinned
 * fingerprint the JVM's own trust store and hostname check apply, which is what a pool holding a
 * certificate from a real CA wants. With a fingerprint, the connection succeeds only against that one
 * certificate. The trust-all context this class used to carry is gone: "trust this pool's self-signed
 * certificate" and "trust every certificate in the world" were the same switch, and only the second of
 * those is what the code did.
 */
final class HttpTransport implements JsonRpcTransport {

    // One HttpClient per operation would allocate a fresh thread and connection pool each time. These are
    // immutable and thread-safe, so share them across the JVM: the verified client as a singleton, and one
    // pinned client per distinct fingerprint. The pinned map is keyed by fingerprint rather than by pool
    // URL because the certificate, not the address, is what the client is built around -- two clouds
    // pointing at the same pool share one client, and a re-pinned pool gets a new one.
    private static final HttpClient SHARED =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private static final Map<String, HttpClient> PINNED = new ConcurrentHashMap<>();

    private final HttpClient http;
    private final URI endpoint;

    /**
     * @param poolUrl base URL of the pool; {@code /jsonrpc} is appended.
     * @param certificateFingerprint SHA-256 fingerprint of the certificate this pool is expected to
     *     present, in any form {@link CertificateFingerprint#normalize} accepts. Null or blank means
     *     ordinary verification against the JVM trust store.
     */
    HttpTransport(String poolUrl, @CheckForNull String certificateFingerprint) {
        Objects.requireNonNull(poolUrl, "poolUrl");
        this.endpoint = URI.create(poolUrl.replaceAll("/+$", "") + "/jsonrpc");
        this.http = certificateFingerprint == null || certificateFingerprint.isBlank()
                ? SHARED
                : PINNED.computeIfAbsent(
                        CertificateFingerprint.normalize(certificateFingerprint), HttpTransport::pinnedClient);
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

    @Override
    public String post(String requestBody) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        int status = resp.statusCode();
        if (status / 100 != 2) {
            // A proxy error page or an auth failure is HTML or plain text, not a JSON-RPC envelope.
            // Fail here with the status so the operator sees "HTTP 502" rather than "malformed response".
            String body = resp.body() == null ? "" : resp.body();
            throw new IOException(
                    "HTTP " + status + " from " + endpoint + ": " + body.substring(0, Math.min(body.length(), 200)));
        }
        String responseBody = resp.body();
        if (responseBody == null) {
            // ofString() should not, but HttpResponse#body is nullable; treat an absent body as an
            // IO failure so it surfaces as a transport error rather than a null slipping upward.
            throw new IOException("empty response body from " + endpoint);
        }
        return responseBody;
    }
}
