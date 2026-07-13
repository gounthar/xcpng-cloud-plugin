package io.jenkins.plugins.xcpng.client;

import java.io.IOException;

/**
 * The one line that actually touches the network: send a JSON-RPC request body, return the response
 * body. Split out from {@link XapiClient} so the verb and error-envelope logic can be tested against
 * recorded JSON fixtures without a pool, and so the TLS/HTTP concerns live in one small place.
 */
interface JsonRpcTransport {

    /**
     * POST {@code requestBody} to the pool's {@code /jsonrpc} endpoint and return the response body.
     *
     * @throws IOException on any connect, write, or read failure (including a truncated body read).
     */
    String post(String requestBody) throws IOException;
}
