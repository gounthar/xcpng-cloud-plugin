package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link RestTransport} over HTTPS to a Xen Orchestra appliance.
 *
 * <p>Authentication is a <b>cookie</b>, not a bearer header, and that is the first thing that trips you
 * up: {@code Cookie: authenticationToken=<token>}. A token minted through
 * {@code POST /rest/v0/users/me/authentication_tokens} sent as {@code Authorization: Bearer} is simply
 * not authenticated, and XO answers 401 with the same body it gives a wrong token, so the mistake reads
 * as a bad credential rather than as a wrong header.
 *
 * <p>TLS trust is {@link TrustedHttpClients}', shared with the XAPI transport. There is no third mode
 * that accepts an unrecognised certificate.
 */
final class HttpRestTransport implements RestTransport {

    private final HttpClient http;
    private final String base;
    private final String token;

    /**
     * @param baseUrl base URL of the appliance, e.g. {@code https://192.168.1.5}. A trailing slash is
     *     trimmed so callers can pass either form.
     * @param token XO authentication token, resolved from the credential store at point of use.
     * @param certificateFingerprint SHA-256 fingerprint of the certificate the appliance is expected to
     *     present, or null for ordinary verification against the JVM trust store.
     */
    HttpRestTransport(@NonNull String baseUrl, @NonNull String token, @CheckForNull String certificateFingerprint) {
        this.base = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.token = Objects.requireNonNull(token, "token");
        this.http = TrustedHttpClients.forFingerprint(certificateFingerprint);
    }

    @Override
    @NonNull
    public RestResponse send(
            @NonNull String method, @NonNull String path, @CheckForNull String jsonBody, @NonNull Duration timeout)
            throws IOException {
        HttpRequest.BodyPublisher publisher =
                jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Cookie", "authenticationToken=" + token)
                .header("Accept", "application/json")
                .method(method, publisher);
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        // Unlike the JSON-RPC transport, a non-2xx is not an IOException here. XO puts a readable
        // {"error": ...} envelope on its failures and the client branches on the status, so turning a 404
        // into a transport error would throw away exactly the signal a teardown needs.
        return new RestResponse(resp.statusCode(), resp.body() == null ? "" : resp.body());
    }
}
