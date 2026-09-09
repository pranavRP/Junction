package io.junction.it;

import io.junction.config.RouteConfig;
import io.junction.config.Strategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4: admission control over real sockets.
 *
 * <p>These tests are deterministic rather than statistical (SUR-003). Load is not
 * offered and then measured — the limit is filled by requests that are still in
 * flight, confirmed through {@code inFlight()}, and only then is the shed path
 * exercised. A test that fired N requests and counted how many came back 503
 * would be asserting the scheduler's timing, not the proxy's behaviour.
 */
class AdmissionIntegrationTest {

    /** Long enough that a held permit is still held while the assertions run. */
    private static final String SLOW = "X-Chaos-Delay: 2000\r\n";

    @Test
    void overCapacityIsShedWithA503AndRetryAfter() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withMaxInFlight(s, 2))) {
            ExecutorService holders = Executors.newFixedThreadPool(2);
            List<Future<RawHttp.Response>> held = hold(holders, h, 2);
            try {
                awaitInFlight(h, 2);

                for (int i = 0; i < 4; i++) {
                    try (RawHttp c = new RawHttp(h.port())) {
                        c.write("GET /x HTTP/1.1\r\nHost: h\r\n\r\n");
                        RawHttp.Response r = c.readResponse();
                        assertEquals(503, r.status(), "request " + i + " is over the limit of 2");
                        assertEquals("1", r.headers().get("retry-after"));
                        assertEquals("over_capacity", r.headers().get("x-junction-reason"));
                    }
                }

                for (Future<RawHttp.Response> f : held) {
                    RawHttp.Response r = f.get(30, TimeUnit.SECONDS);
                    assertEquals(200, r.status(),
                            "an admitted request is served normally while others are shed: "
                                    + r.headers());
                }
            } finally {
                holders.shutdownNow();
            }

            awaitInFlight(h, 0);
            try (RawHttp c = new RawHttp(h.port())) {
                c.write("GET /after HTTP/1.1\r\nHost: h\r\n\r\n");
                assertEquals(200, c.readResponse().status(),
                        "the limit is concurrency, so it clears as requests finish");
            }
        }
    }

    /**
     * The shed response is the one Junction-generated error that does not close.
     * At twice the knee, closing would charge every shed client a TCP handshake
     * at exactly the load where handshakes are what we cannot afford.
     */
    @Test
    void aShedBodilessRequestLeavesTheConnectionUsable() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withMaxInFlight(s, 1))) {
            ExecutorService holders = Executors.newSingleThreadExecutor();
            List<Future<RawHttp.Response>> held = hold(holders, h, 1);
            try (RawHttp c = new RawHttp(h.port())) {
                awaitInFlight(h, 1);

                c.write("GET /shed HTTP/1.1\r\nHost: h\r\n\r\n");
                RawHttp.Response shed = c.readResponse();
                assertEquals(503, shed.status());
                assertEquals("keep-alive", shed.headers().get("connection").toLowerCase());

                RawHttp.Response slow = held.get(0).get(30, TimeUnit.SECONDS);
                assertEquals(200, slow.status(), "held: " + slow.headers());

                awaitInFlight(h, 0);

                // Same TCP connection, after a shed: the framing survived.
                c.write("GET /again HTTP/1.1\r\nHost: h\r\n\r\n");
                RawHttp.Response again = c.readResponse();
                assertEquals(200, again.status(), "reused after a shed: " + again.headers());
            } finally {
                holders.shutdownNow();
            }
        }
    }

    /** A body we have already refused is not worth draining to save a socket. */
    @Test
    void aShedRequestCarryingABodyIsClosed() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withMaxInFlight(s, 1))) {
            ExecutorService holders = Executors.newSingleThreadExecutor();
            List<Future<RawHttp.Response>> held = hold(holders, h, 1);
            try (RawHttp c = new RawHttp(h.port())) {
                awaitInFlight(h, 1);

                c.write("POST /shed HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello");
                RawHttp.Response shed = c.readResponse();
                assertEquals(503, shed.status());
                assertEquals("close", shed.headers().get("connection").toLowerCase());
                assertTrue(c.isClosedByPeer(), "an unread body means the framing cannot be trusted");

                RawHttp.Response slow = held.get(0).get(30, TimeUnit.SECONDS);
                assertEquals(200, slow.status(), "held: " + slow.headers());
            } finally {
                holders.shutdownNow();
            }
        }
    }

    /**
     * A permit leaked on an error path would lower the ceiling one request at a
     * time until the proxy shed everything — an outage that looks exactly like
     * admission control working.
     */
    @Test
    void aRejectedRequestReturnsItsPermit() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(
                s -> ProxyHarness.withMaxInFlight(s, 1),
                List.of(new RouteConfig("*", "/api", "api")), 1, Strategy.P2C)) {

            try (RawHttp c = new RawHttp(h.port())) {
                c.write("GET /nope HTTP/1.1\r\nHost: h\r\n\r\n");
                assertEquals(404, c.readResponse().status());
            }
            awaitInFlight(h, 0);

            try (RawHttp c = new RawHttp(h.port())) {
                c.write("GET /api/x HTTP/1.1\r\nHost: h\r\n\r\n");
                assertEquals(200, c.readResponse().status(), "the one permit is still available");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Occupies {@code n} permits with requests the backend will sit on. */
    private static List<Future<RawHttp.Response>> hold(ExecutorService pool, ProxyHarness h, int n) {
        List<Future<RawHttp.Response>> held = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<RawHttp.Response> slow = () -> {
                try (RawHttp c = new RawHttp(h.port())) {
                    c.write("GET /slow HTTP/1.1\r\nHost: h\r\n" + SLOW + "\r\n");
                    return c.readResponse();
                }
            };
            held.add(pool.submit(slow));
        }
        return held;
    }

    /**
     * Waits for the permit count to settle rather than asserting it outright.
     *
     * <p>A response is on the wire a moment before the proxy has finished its own
     * bookkeeping — the last chunk is flushed, and only then is the permit
     * returned. A client that has read its response therefore proves nothing
     * about the counter yet, and an immediate assertion on it would be asserting
     * that race rather than the behaviour (SUR-003).
     */
    private static void awaitInFlight(ProxyHarness h, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (h.server().admission().inFlight() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("in flight settled at "
                        + h.server().admission().inFlight() + ", expected " + expected);
            }
            Thread.sleep(5);
        }
    }
}
