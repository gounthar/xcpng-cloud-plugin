package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;

/**
 * The one line that actually touches the network for the Xen Orchestra backend: send a request, return
 * the status and the body. Split out from {@link XoRestClient} so the route and error-envelope logic can
 * be tested against recorded responses without an appliance, and so the HTTP concerns live in one small
 * place. The sibling of {@link JsonRpcTransport}, which does the same job for XAPI.
 *
 * <p>Wider than its sibling by exactly what REST needs and JSON-RPC does not: a method, a path, and the
 * response <em>status</em>. The status is not decoration. XO answers a request for an object that is gone
 * with a 404 and a body that names nothing in particular, and a teardown reaching an already-destroyed VM
 * has to tell that from a genuine failure; matching on the message text instead would also match a 404
 * quoted inside some other failure.
 */
interface RestTransport {

    /**
     * @param method HTTP method, e.g. {@code GET}.
     * @param path absolute path on the appliance, including the query string, e.g.
     *     {@code /rest/v0/vms/abc?fields=power_state}. Callers percent-encode their own path segments.
     * @param jsonBody request body, already serialized, or null to send none.
     * @param timeout how long to wait for the response. Per call rather than per transport because the
     *     lifecycle verbs are minutes-long and the reads are sub-second, and one timeout that suits both
     *     is either too short to clone or too long to notice an appliance that has gone away.
     * @throws IOException on any connect, write, or read failure.
     */
    @NonNull
    RestResponse send(
            @NonNull String method, @NonNull String path, @CheckForNull String jsonBody, @NonNull Duration timeout)
            throws IOException;

    /** An answer from the appliance: the HTTP status, and the body (empty rather than null when there was none). */
    record RestResponse(int status, @NonNull String body) {

        public RestResponse {
            if (body == null) {
                body = "";
            }
        }

        boolean isSuccess() {
            return status / 100 == 2;
        }
    }
}
