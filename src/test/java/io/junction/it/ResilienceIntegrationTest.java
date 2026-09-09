package io.junction.it;

import io.junction.backend.BackendPool;
import io.junction.config.BreakerConfig;
import io.junction.config.RetryConfig;
import io.junction.config.RouteConfig;
import io.junction.config.Strategy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 end to end: the retry budget, the breaker, and panic mode against a
 * real proxy over real sockets.
 *
 * <p><b>Probes are dormant in these tests on purpose.</b> With fast active
 * probes, a dead backend is ejected within a few hundred milliseconds and the
 * data path stops choosing it — which would make every assertion here pass
 * without exercising any of the machinery it claims to test. The point of Phase 3
 * is precisely what happens in the window active probing cannot cover (MEA-012),
 * so these tests hold that window open.
 */
class ResilienceIntegrationTest {

    /** One request on its own connection: Junction closes after every error it generates. */
    private static int status(int port) throws IOException {
        return request(port, "");
    }

    private static int request(int port, String extraHeader) throws IOException {
        try (RawHttp conn = new RawHttp(port)) {
            conn.write("GET /x HTTP/1.1\r\nHost: h\r\n" + extraHeader + "\r\n");
            return conn.readResponse().status();
        }
    }

    // ------------------------------------------------------- THE PHASE 3 GATE

    /**
     * <b>Gate: a total backend outage must not produce more than 1.1x normal
     * upstream volume.</b>
     *
     * <p>Every backend is down, so every request fails and every request wants to
     * retry. Without a budget that is a proxy turning an outage into a
     * self-inflicted flood, at exactly the moment the backends have the least
     * capacity to absorb one. The budget is what makes a retry policy safe to
     * leave switched on.
     */
    @Test
    void aTotalOutageCannotAmplifyUpstreamVolume() throws Exception {
        int requests = 200;
        try (ProxyHarness h = ProxyHarness.startWithPolicy(
                3, ProxyHarness.DORMANT_PROBES, 0,
                BreakerConfig.disabled(),
                new RetryConfig(3, 10, 0))) {

            h.backends().forEach(b -> b.stop());

            for (int i = 0; i < requests; i++) {
                assertEquals(502, status(h.port()), "every request must still fail honestly");
            }

            long attempts = pool(h).attempts();
            assertTrue(attempts <= requests * 11 / 10,
                    "upstream attempts " + attempts + " for " + requests
                            + " client requests exceeds the 1.1x gate");
            assertTrue(attempts > requests,
                    "some retries must actually have happened, or the gate is vacuous");
            System.out.printf("[MEA-005] total outage: %d client requests produced %d upstream "
                    + "attempts (%.3fx) against a 1.100x gate%n",
                    requests, attempts, (double) attempts / requests);
        }
    }

    /**
     * The control for the gate above. Retries off means attempts equal requests
     * exactly, which is what proves the surplus in the gate test is retries rather
     * than some other source of upstream traffic.
     */
    @Test
    void withRetriesOffUpstreamVolumeEqualsRequestVolume() throws Exception {
        int requests = 50;
        try (ProxyHarness h = ProxyHarness.startWithPolicy(
                3, ProxyHarness.DORMANT_PROBES, 0,
                BreakerConfig.disabled(), RetryConfig.disabled())) {

            h.backends().forEach(b -> b.stop());
            for (int i = 0; i < requests; i++) {
                assertEquals(502, status(h.port()));
            }

            assertEquals(requests, pool(h).attempts(), "one attempt per request, no more");
        }
    }

    /**
     * The budget has no off switch, only a ceiling — and that is the point.
     *
     * <p>{@code budget_percent} is validated to 0..100, so even the most permissive
     * policy the config can express caps amplification at 2x rather than the 3x
     * that {@code max_attempts: 3} looks like it promises. An operator cannot
     * accidentally configure the failure mode this component exists to prevent;
     * the worst they can do is widen it.
     */
    @Test
    void theBudgetCapsAmplificationEvenAtItsMostPermissiveSetting() throws Exception {
        int requests = 100;
        try (ProxyHarness h = ProxyHarness.startWithPolicy(
                3, ProxyHarness.DORMANT_PROBES, 0,
                BreakerConfig.disabled(), new RetryConfig(3, 100, 0))) {

            h.backends().forEach(b -> b.stop());
            for (int i = 0; i < requests; i++) {
                assertEquals(502, status(h.port()));
            }

            long attempts = pool(h).attempts();
            assertTrue(attempts <= requests * 2,
                    "even a 100% budget must hold 2 attempts per request, got " + attempts);
            assertTrue(attempts > requests * 3 / 2,
                    "and it should genuinely spend that budget, got " + attempts);
        }
    }

    /**
     * A retry is only worth having if it can actually rescue a request.
     *
     * <p>Round robin rather than the default P2C, so the assertion is exact
     * instead of statistical: the retry is guaranteed to land on the next backend
     * in the schedule, which is the live one. Asserting "most of 20" against a
     * random strategy would be a test that fails once a fortnight and teaches
     * everyone to re-run it.
     */
    @Test
    void aRetryRecoversEveryRequestWhileOneBackendIsStillUp() throws Exception {
        try (ProxyHarness h = ProxyHarness.start(
                UnaryOperator.identity(), List.of(new RouteConfig("*", "/", "api")),
                2, Strategy.ROUND_ROBIN, ProxyHarness.DORMANT_PROBES, 0,
                BreakerConfig.disabled(), new RetryConfig(2, 100, 100))) {

            h.backend(0).stop();

            for (int i = 0; i < 20; i++) {
                assertEquals(200, status(h.port()),
                        "request " + i + " had a live backend one retry away");
            }
            assertTrue(pool(h).attempts() > 20, "and it took retries to get there");
        }
    }

    // ---------------------------------------------------------------- breaker

    /**
     * The gap MEA-012 measured: active probing cannot react faster than its own
     * interval, so 76 requests died inside the ejection window. The breaker reacts
     * in requests instead, with no probe involved at all.
     */
    @Test
    void theBreakerEjectsAFailingBackendWithoutWaitingForAProbe() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithPolicy(
                2, ProxyHarness.DORMANT_PROBES, 0,
                new BreakerConfig(3, 60_000, 60_000, 1), RetryConfig.disabled())) {

            h.backend(0).stop();

            for (int i = 0; i < 40; i++) {
                status(h.port());
            }

            assertEquals("open", pool(h).backends().get(0).breaker().label(),
                    "a backend refusing every connection must trip its breaker");
            assertEquals(1, pool(h).selectableCount(), "and leave rotation");

            // With the dead backend barred, the survivor takes everything.
            for (int i = 0; i < 10; i++) {
                assertEquals(200, status(h.port()));
            }
        }
    }

    @Test
    void theBreakerCountsFiveHundredsButNotFourHundreds() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithPolicy(
                1, ProxyHarness.DORMANT_PROBES, 0,
                new BreakerConfig(3, 60_000, 60_000, 1), RetryConfig.disabled())) {

            for (int i = 0; i < 10; i++) {
                assertEquals(404, request(h.port(), "X-Chaos-Status: 404\r\n"));
            }
            assertEquals("closed", breakerLabel(h),
                    "client errors say nothing about backend health");

            for (int i = 0; i < 3; i++) {
                request(h.port(), "X-Chaos-Status: 503\r\n");
            }
            assertEquals("open", breakerLabel(h), "the backend reporting its own failure does");
        }
    }

    // ------------------------------------------------------------------ panic

    /**
     * Panic trades a certain 503 for an uncertain 200. With every backend ejected
     * the alternative is refusing every request, so trying a backend that only
     * looks dead is strictly better than not trying at all.
     */
    @Test
    void panicKeepsServingWhenEveryBackendLooksUnhealthy() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithPolicy(
                2, ProxyHarness.DORMANT_PROBES, 50,
                new BreakerConfig(2, 60_000, 60_000, 1), RetryConfig.disabled())) {

            // Trip both breakers with real 5xx traffic. The backends themselves
            // stay up, so a request that gets through in panic really does work.
            for (int i = 0; i < 8; i++) {
                request(h.port(), "X-Chaos-Status: 500\r\n");
            }

            // Panic is sampled inside the loop rather than after it, because panic
            // working is what ends it: the requests it lets through succeed, those
            // successes close the breakers, and the pool leaves panic on its own.
            // Asserting at the end would be asserting that the recovery failed.
            boolean sawPanic = false;
            Set<Integer> statuses = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                statuses.add(status(h.port()));
                sawPanic |= pool(h).inPanic();
            }

            assertTrue(sawPanic, "both breakers open must put the pool in panic");
            assertTrue(statuses.contains(200),
                    "panic must keep routing, and these backends still answer; got " + statuses);

            // Panic lifts the moment it is no longer justified, which is as soon as
            // ONE backend is back — half of two is the threshold. The other backend
            // is left to its own cooldown rather than being carried out of panic
            // with it, and that is the right shape: panic is an emergency, not a
            // recovery mechanism.
            assertFalse(pool(h).inPanic(), "panic must not latch once the pool can cope again");
            assertEquals(1, pool(h).selectableCount(),
                    "one recovered backend is exactly enough to leave panic");
        }
    }

    @Test
    void withPanicOffAFullyEjectedPoolReturnsServiceUnavailable() throws Exception {
        try (ProxyHarness h = ProxyHarness.startWithPolicy(
                2, ProxyHarness.DORMANT_PROBES, 0,
                new BreakerConfig(2, 60_000, 60_000, 1), RetryConfig.disabled())) {

            for (int i = 0; i < 8; i++) {
                request(h.port(), "X-Chaos-Status: 500\r\n");
            }

            assertEquals(503, status(h.port()), "no panic configured, so no backend is offered");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String breakerLabel(ProxyHarness h) {
        return pool(h).backends().get(0).breaker().label();
    }

    private static BackendPool pool(ProxyHarness h) {
        return h.server().pools().byName("api").orElseThrow();
    }
}
