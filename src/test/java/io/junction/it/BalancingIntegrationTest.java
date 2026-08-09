package io.junction.it;

import io.junction.config.Strategy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 end to end: balancing across a real pool, ejection of a real backend,
 * and reuse of pooled upstream connections.
 *
 * <p>No sleeping (R-23). The ejection test makes progress on every iteration by
 * issuing a real request, and is bounded by a wall-clock deadline — the gate is
 * itself a statement about elapsed time, so time is the thing being measured
 * rather than something waited on.
 */
class BalancingIntegrationTest {

    /** A client that reconnects when Junction closes the connection under it. */
    private static final class Client implements AutoCloseable {
        private final int port;
        private RawHttp conn;

        Client(int port) throws IOException {
            this.port = port;
            this.conn = new RawHttp(port);
        }

        RawHttp.Response get(String path) throws IOException {
            try {
                conn.write("GET " + path + " HTTP/1.1\r\nHost: h\r\n\r\n");
                return conn.readResponse();
            } catch (IOException retry) {
                conn.close();
                conn = new RawHttp(port);
                conn.write("GET " + path + " HTTP/1.1\r\nHost: h\r\n\r\n");
                return conn.readResponse();
            }
        }

        @Override
        public void close() throws IOException {
            conn.close();
        }
    }

    @Test
    void spreadsTrafficAcrossEveryBackendInThePool() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithBackends(3, Strategy.P2C);
             Client c = new Client(h.port())) {

            Map<String, Integer> hits = new HashMap<>();
            for (int i = 0; i < 120; i++) {
                RawHttp.Response r = c.get("/");
                assertEquals(200, r.status());
                hits.merge(r.headers().get("X-Backend-Id"), 1, Integer::sum);
            }
            assertEquals(3, hits.size(), "every backend must receive traffic, got " + hits);
        }
    }

    @Test
    void roundRobinVisitsEveryBackend() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithBackends(3, Strategy.ROUND_ROBIN);
             Client c = new Client(h.port())) {

            Map<String, Integer> hits = new HashMap<>();
            for (int i = 0; i < 90; i++) {
                hits.merge(c.get("/").headers().get("X-Backend-Id"), 1, Integer::sum);
            }
            assertEquals(3, hits.size(), hits.toString());
            hits.forEach((id, n) -> assertTrue(n >= 20,
                    id + " got only " + n + " of 90 with equal weights: " + hits));
        }
    }

    /**
     * Pooled upstream connections outlive the downstream connection that created
     * them — the change from Phase 1's one-upstream-per-downstream pinning. A
     * client connection seeing {@code X-Conn-Requests > 1} on its very first
     * request proves it inherited a socket some earlier client had used.
     *
     * <p><b>Why this needs many connections rather than two.</b> The pool is
     * partitioned per EventLoop, so a connection can only inherit a socket left
     * behind on the loop it happens to land on. Netty assigns loops round-robin
     * across roughly {@code 2 × cores} workers, so two consecutive client
     * connections almost never share one. Reuse is therefore a property of the
     * population, not a guarantee for any single connection — which is the
     * honest form of the claim, and the same partitioning that OPQ-002 flags for
     * measurement (up to {@code loops × maxIdle} sockets per backend).
     */
    @Test
    void upstreamConnectionsAreReusedAcrossDifferentClientConnections() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithBackends(1, Strategy.P2C)) {
            int inherited = 0;
            int maxSeen = 0;

            // Enough connections to wrap the worker pool several times over on any
            // plausible core count.
            for (int i = 0; i < 120; i++) {
                try (Client c = new Client(h.port())) {
                    RawHttp.Response r = c.get("/");
                    assertEquals(200, r.status());
                    int connRequests = Integer.parseInt(r.headers().get("X-Conn-Requests"));
                    maxSeen = Math.max(maxSeen, connRequests);
                    if (connRequests > 1) {
                        inherited++;
                    }
                }
            }

            assertTrue(inherited > 0,
                    "no client connection ever inherited a pooled upstream socket over 120 "
                            + "attempts; highest X-Conn-Requests seen was " + maxSeen);
            System.out.printf("[pool] %d of 120 fresh client connections inherited a pooled "
                    + "upstream socket (max reuse depth %d)%n", inherited, maxSeen);
        }
    }

    /**
     * <b>The Phase 2 gate.</b> Three backends, one broken mid-load; the client
     * error rate must return to zero within 10 seconds and stay there.
     *
     * <p>Health config in the harness probes every 200ms with an unhealthy
     * threshold of 2, so the expected ejection latency is ~400ms plus one probe
     * of slack — comfortably inside the gate, which is the point of measuring it
     * rather than asserting it.
     */
    @Test
    void killingABackendStopsClientErrorsWithinTenSeconds() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithBackends(3, Strategy.P2C);
             Client c = new Client(h.port())) {

            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 60; i++) {
                RawHttp.Response r = c.get("/");
                assertEquals(200, r.status(), "pool must be healthy before the fault");
                seen.add(r.headers().get("X-Backend-Id"));
            }
            assertEquals(3, seen.size(), "all three backends must be live first, saw " + seen);

            long faultAt = System.nanoTime();
            h.backend(1).setHealthy(false);

            // Ride out the ejection: count errors until the pool has settled.
            int errors = 0;
            int consecutiveOk = 0;
            long deadline = faultAt + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && consecutiveOk < 50) {
                if (c.get("/").status() == 200) {
                    consecutiveOk++;
                } else {
                    consecutiveOk = 0;
                    errors++;
                }
            }
            long settledMs = Duration.ofNanos(System.nanoTime() - faultAt).toMillis();

            assertTrue(consecutiveOk >= 50,
                    "client errors never stopped within 10s (" + errors + " errors seen)");
            System.out.printf("[MEA-012] backend killed: %d client errors, "
                    + "error rate back to zero after %d ms%n", errors, settledMs);

            // Steady state: the ejected backend must stay out.
            for (int i = 0; i < 150; i++) {
                RawHttp.Response r = c.get("/");
                assertEquals(200, r.status(), "an ejected backend leaked traffic after settling");
                assertTrue(!"b1".equals(r.headers().get("X-Backend-Id")),
                        "request routed to the ejected backend");
            }
        }
    }

    /** Ejection must be reversible: a repaired backend comes back on its own. */
    @Test
    void repairedBackendIsReadmittedWithoutRestart() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithBackends(3, Strategy.ROUND_ROBIN);
             Client c = new Client(h.port())) {

            h.backend(1).setHealthy(false);

            int consecutiveOk = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && consecutiveOk < 40) {
                consecutiveOk = c.get("/").status() == 200 ? consecutiveOk + 1 : 0;
            }
            assertTrue(consecutiveOk >= 40, "backend was never ejected");

            h.backend(1).setHealthy(true);

            boolean returned = false;
            deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && !returned) {
                returned = "b1".equals(c.get("/").headers().get("X-Backend-Id"));
            }
            assertTrue(returned, "a repaired backend never returned to rotation within 10s");
        }
    }

    /** With every backend down the client gets a clean 503, not a hang or a crash. */
    @Test
    void totalPoolOutageProducesAClean503() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithBackends(2, Strategy.P2C);
             Client c = new Client(h.port())) {

            h.backend(0).setHealthy(false);
            h.backend(1).setHealthy(false);

            int status = 0;
            String reason = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                RawHttp.Response r = c.get("/");
                if (r.status() == 503 && "no_healthy_backend".equals(
                        r.headers().get("X-Junction-Reason"))) {
                    status = r.status();
                    reason = r.headers().get("X-Junction-Reason");
                    break;
                }
            }
            assertEquals(503, status, "a fully down pool must shed with 503");
            assertEquals("no_healthy_backend", reason,
                    "the reason must distinguish a down pool from a config error");
        }
    }
}
