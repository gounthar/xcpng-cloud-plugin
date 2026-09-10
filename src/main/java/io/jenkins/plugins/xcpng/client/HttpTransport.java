package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * The only part of the XAPI client that touches the network: POST a JSON-RPC body to the pool's
 * {@code /jsonrpc} endpoint and return the response body. Kept separate from {@link XapiClient} so the
 * verb and error-envelope logic can be tested against recorded JSON fixtures without a pool, and so the
 * HTTP concerns live in one small place.
 *
 * <p>TLS trust is not decided here. It lives in {@link TrustedHttpClients}, which this and the Xen
 * Orchestra transport share, so there is one place a certificate is accepted or refused rather than one
 * per backend.
 */
final class HttpTransport implements JsonRpcTransport {

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
        this.http = TrustedHttpClients.forFingerprint(certificateFingerprint);
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
