package io.junction.it;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 1 behaviour, asserted over real sockets. */
class ProxyIntegrationTest {

    @Test
    void proxiesASimpleRequestEndToEnd() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n");
            RawHttp.Response r = c.readResponse();

            assertEquals(200, r.status());
            assertEquals("b0", r.headers().get("X-Backend-Id"));
            assertTrue(r.bodyText().startsWith("ok from b0"), r.bodyText());
        }
    }

    /**
     * The client-side half of keep-alive: two requests, one TCP connection.
     */
    @Test
    void reusesTheDownstreamConnectionAcrossRequests() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET /one HTTP/1.1\r\nHost: h\r\n\r\n");
            assertEquals(200, c.readResponse().status());

            c.write("GET /two HTTP/1.1\r\nHost: h\r\n\r\n");
            assertEquals(200, c.readResponse().status());
        }
    }

    /**
     * The upstream half, and the direct answer to OPQ-006. X-Conn-Requests is the
     * backend's own count of requests on that TCP connection, so a value of 2
     * proves Junction reused the upstream socket rather than reconnecting — the
     * behaviour the Phase 0 spike lacked.
     */
    @Test
    void reusesTheUpstreamConnectionAcrossRequests() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET /one HTTP/1.1\r\nHost: h\r\n\r\n");
            RawHttp.Response first = c.readResponse();
            c.write("GET /two HTTP/1.1\r\nHost: h\r\n\r\n");
            RawHttp.Response second = c.readResponse();

            assertEquals("1", first.headers().get("X-Conn-Requests"));
            assertEquals("2", second.headers().get("X-Conn-Requests"),
                    "second request must arrive on the same upstream connection");
        }
    }

    @Test
    void streamsAChunkedUploadThrough() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("POST /upload HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n");
            c.write("5\r\nhello\r\n");
            c.write("6\r\n world\r\n");
            c.write("0\r\n\r\n");

            RawHttp.Response r = c.readResponse();
            assertEquals(200, r.status());
            assertEquals("11", r.headers().get("X-Received-Bytes"),
                    "backend must receive every uploaded byte");
        }
    }

    @Test
    void streamsAChunkedDownloadThrough() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET /download HTTP/1.1\r\nHost: h\r\n"
                    + "X-Chaos-Chunks: 5\r\nX-Chaos-Size: 500\r\n\r\n");

            RawHttp.Response r = c.readResponse();
            assertEquals(200, r.status());
            assertEquals("chunked", r.headers().get("Transfer-Encoding"));
            assertEquals(500, r.body().length);
            assertNull(r.headers().get("Content-Length"),
                    "a chunked response must not also carry Content-Length");
        }
    }

    @Test
    void addsForwardedAndRequestIdHeaders() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET / HTTP/1.1\r\nHost: h\r\n\r\n");
            RawHttp.Response r = c.readResponse();

            assertEquals(200, r.status());
            assertNotNull(r.headers().get("X-Request-ID"), "response must echo a request id");
        }
    }

    @Test
    void oversizedHeadersAreRejectedWith431() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withMaxHeaderBytes(s, 1024));
             RawHttp c = new RawHttp(h.port())) {

            String padding = "x".repeat(4096);
            c.write("GET / HTTP/1.1\r\nHost: h\r\nX-Big: " + padding + "\r\n\r\n");

            assertEquals(431, c.readResponse().status());
        }
    }

    @Test
    void oversizedUriIsRejectedWith414() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withMaxUriLength(s, 256));
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET /" + "a".repeat(2048) + " HTTP/1.1\r\nHost: h\r\n\r\n");

            assertEquals(414, c.readResponse().status());
        }
    }

    @Test
    void oversizedBodyIsRejectedWith413() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withMaxBodyBytes(s, 1024));
             RawHttp c = new RawHttp(h.port())) {

            byte[] body = new byte[64 * 1024];
            c.write("POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: " + body.length + "\r\n\r\n");
            try {
                c.writeBytes(body);
                c.flush();
            } catch (java.io.IOException expected) {
                // Junction may close as soon as it decides to reject, before we
                // finish writing. Either ordering is correct.
            }

            assertEquals(413, c.readResponse().status());
        }
    }

    /** A backend that never answers must produce 504, not a hung client. */
    @Test
    void slowBackendProduces504() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withRequestTimeout(s, 300));
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET / HTTP/1.1\r\nHost: h\r\nX-Chaos-Delay: 10000\r\n\r\n");

            RawHttp.Response r = c.readResponse();
            assertEquals(504, r.status());
            assertEquals("request_timeout", r.headers().get("X-Junction-Reason"));
        }
    }

    @Test
    void unroutableRequestProduces404() throws Exception {
        // Only /api is routed, so /elsewhere has nowhere to go.
        try (ProxyHarness h = ProxyHarness.startWithRoutes(
                java.util.List.of(new io.junction.config.RouteConfig("*", "/api", "api")));
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET /elsewhere HTTP/1.1\r\nHost: h\r\n\r\n");
            RawHttp.Response r = c.readResponse();

            assertEquals(404, r.status());
            assertEquals("no_route", r.headers().get("X-Junction-Reason"));
        }
    }

    @Test
    void backendStatusIsPassedThroughUnchanged() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET / HTTP/1.1\r\nHost: h\r\nX-Chaos-Status: 503\r\n\r\n");
            assertEquals(503, c.readResponse().status());
        }
    }

    @Test
    void hopByHopHeadersDoNotReachTheBackend() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET / HTTP/1.1\r\nHost: h\r\n"
                    + "Connection: keep-alive, X-Secret-Hop\r\n"
                    + "X-Secret-Hop: leaked\r\n\r\n");

            assertEquals(200, c.readResponse().status());
        }
    }

    @Test
    void clientRequestingCloseGetsConnectionClosed() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            c.write("GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
            RawHttp.Response r = c.readResponse();

            assertEquals(200, r.status());
            assertEquals("close", r.headers().get("Connection"));
            assertTrue(c.isClosedByPeer(), "server must close when the client asked it to");
        }
    }

    /**
     * A client that announces a body and then stops sending is genuinely idle —
     * autoRead is on and we are waiting on it — so the idle timer must fire.
     * This is the branch of the idle guard that still times out; the other branch
     * (silence caused by our own backpressure, which must NOT time out) needs a
     * throttled reader and is asserted in Phase 4's slow-client tests.
     */
    @Test
    void clientThatStopsMidRequestIsTimedOutWith408() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withIdleTimeout(s, 400));
             RawHttp c = new RawHttp(h.port())) {

            c.write("POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: 100\r\n\r\n");
            c.writeBytes("partial".getBytes(StandardCharsets.UTF_8));
            c.flush();

            RawHttp.Response r = c.readResponse();
            assertEquals(408, r.status());
            assertEquals("idle_timeout", r.headers().get("X-Junction-Reason"));
        }
    }

    @Test
    void largeContentLengthBodyRoundTrips() throws Exception {
        try (ProxyHarness h = ProxyHarness.start();
             RawHttp c = new RawHttp(h.port())) {

            byte[] body = "payload".repeat(10_000).getBytes(StandardCharsets.UTF_8);
            c.write("POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: " + body.length + "\r\n\r\n");
            c.writeBytes(body);
            c.flush();

            RawHttp.Response r = c.readResponse();
            assertEquals(200, r.status());
            assertEquals(String.valueOf(body.length), r.headers().get("X-Received-Bytes"));
        }
    }
}
