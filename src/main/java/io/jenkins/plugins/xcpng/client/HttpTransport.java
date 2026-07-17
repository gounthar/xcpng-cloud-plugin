package io.jenkins.plugins.xcpng.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The only part of the client that touches the network: POST a JSON-RPC body to the pool's
 * {@code /jsonrpc} endpoint and return the response body. Kept separate from {@link XapiClient} so the
 * verb and error-envelope logic can be tested against recorded JSON fixtures without a pool, and so the
 * HTTP and TLS concerns live in one small place.
 */
final class HttpTransport implements JsonRpcTransport {

    // One HttpClient per operation would allocate a fresh thread and connection pool each time.
    // These are immutable and thread-safe, so share them across the JVM. The verified client is the
    // default; the trust-all client is built lazily (see TrustAllHolder) so its weaker SSLContext is
    // only ever constructed when a caller actually opts into trustSelfSigned, and a failure building
    // it can never take down the verified path.
    private static final HttpClient SHARED =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    /** Holds the trust-all client so it is constructed on first use, not at class load. */
    private static final class TrustAllHolder {
        static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .sslContext(trustAllContext())
                .sslParameters(trustAllParameters())
                .build();
    }

    private final HttpClient http;
    private final URI endpoint;

    HttpTransport(String poolUrl, boolean trustSelfSigned) {
        Objects.requireNonNull(poolUrl, "poolUrl");
        this.endpoint = URI.create(poolUrl.replaceAll("/+$", "") + "/jsonrpc");
        this.http = trustSelfSigned ? TrustAllHolder.CLIENT : SHARED;
    }

    private static SSLParameters trustAllParameters() {
        SSLParameters p = new SSLParameters();
        p.setEndpointIdentificationAlgorithm(null); // also skip hostname verification
        return p;
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

    private static SSLContext trustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(
                    null,
                    new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                    },
                    new SecureRandom());
            return ctx;
        } catch (java.security.GeneralSecurityException e) {
            throw new HypervisorException("cannot build a trust-all SSL context: " + e.getMessage(), e);
        }
    }
}
