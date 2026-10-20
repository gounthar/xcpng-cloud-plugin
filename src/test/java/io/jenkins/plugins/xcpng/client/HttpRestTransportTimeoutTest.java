package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-call timeout has to bound the whole answer, body included. {@code HttpRequest.timeout} alone does
 * not: on JDK 21 and 25 it stops counting once the headers arrive, so an appliance that sends its headers
 * and then stalls would hold a provisioning or teardown thread for as long as the connection stays open.
 *
 * <p>A local server plays that appliance. Plain HTTP is enough: the timer in question is the same with or
 * without TLS, and the trust modes have their own test.
 */
class HttpRestTransportTimeoutTest {

    private static final String PATH = "/rest/v0/pools";
    private static final String BODY = "[{\"id\":\"pool\"}]";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** Long enough that a transport which ignores its timeout is caught, short enough not to stall the build. */
    private static final Duration GIVE_UP = Duration.ofSeconds(10);

    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch hungUp = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService handlers;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext(PATH + "/complete", exchange -> {
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext(PATH + "/stall-before-headers", exchange -> {
            awaitRelease();
            exchange.close();
        });
        server.createContext(PATH + "/stall-after-headers", exchange -> {
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            // Promise the whole body, deliver a few bytes of it, then go quiet with the connection open.
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes, 0, 4);
            out.flush();
            awaitRelease();
            exchange.close();
        });
        server.createContext(PATH + "/trickle", exchange -> {
            // Chunked, one byte at a time and never finishing: the connection is never idle, so only a
            // deadline on the whole answer ends it. The write that fails is the client hanging up.
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                while (release.getCount() > 0) {
                    out.write('[');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException e) {
                hungUp.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (handlers != null) {
            handlers.shutdownNow();
            handlers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * The control: the same server, the same timeout, a body that arrives. Without it the tests below
     * would pass just as well against a transport that could not reach the server at all.
     */
    @Test
    void aCompleteAnswerIsReturned() {
        String body = assertTimeoutPreemptively(GIVE_UP, () -> send("/complete").body());
        assertEquals(BODY, body);
    }

    @Test
    void anAnswerWhoseHeadersNeverArriveTimesOut() {
        assertTimeoutPreemptively(
                GIVE_UP, () -> assertThrows(HttpTimeoutException.class, () -> send("/stall-before-headers")));
    }

    @Test
    void anAnswerWhoseBodyStallsAfterTheHeadersTimesOut() {
        assertTimeoutPreemptively(
                GIVE_UP, () -> assertThrows(HttpTimeoutException.class, () -> send("/stall-after-headers")));
    }

    /**
     * Throwing on time is half of it. The exchange behind the timed-out call has to be abandoned too, or
     * every stalled answer leaves a connection open and a body still being read into memory behind it.
     */
    @Test
    void aTimedOutAnswerHangsUpOnTheAppliance() throws Exception {
        assertTimeoutPreemptively(GIVE_UP, () -> assertThrows(HttpTimeoutException.class, () -> send("/trickle")));
        assertTrue(hungUp.await(5, TimeUnit.SECONDS), "the server was still writing to the abandoned exchange");
    }

    private RestTransport.RestResponse send(String route) throws Exception {
        HttpRestTransport transport = new HttpRestTransport(baseUrl, "a-token", PoolTrust.JVM_DEFAULT);
        return transport.send("GET", PATH + route, null, TIMEOUT);
    }

    private void awaitRelease() {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
