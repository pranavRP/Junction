package io.junction.balance;

import io.junction.backend.BackendRuntime;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The behaviour that distinguishes each strategy from the others. */
class StrategyBehaviourTest {

    // ------------------------------------------------------- round robin

    @Test
    void weightedRoundRobinHitsEachBackendExactlyItsShare() {
        List<BackendRuntime> backends = List.of(
                Backends.backend("a", 5),
                Backends.backend("b", 1),
                Backends.backend("c", 1));
        Balancer rr = new RoundRobinBalancer(backends);

        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 700; i++) {   // 100 full periods of 7
            hits.merge(Backends.idOf(rr.pick("")), 1, Integer::sum);
        }
        assertEquals(500, hits.get("a"));
        assertEquals(100, hits.get("b"));
        assertEquals(100, hits.get("c"));
    }

    /**
     * The reason smooth WRR exists: naive weighted RR with {5,5,1} emits
     * {@code AAAAABBBBBC}, and five back-to-back requests to one host is exactly
     * the burst that trips a breaker. Smooth interleaves to {@code ABABCABABAB}.
     *
     * <p>Weights of {5,1,1} are a poor demonstration — smooth yields
     * {@code AABACAA}, whose longest run is 4 once the period wraps, against 5
     * for naive. The benefit depends on the weight set, so the test uses a set
     * where the claim is actually visible.
     */
    @Test
    void weightedRoundRobinDoesNotEmitLongRuns() {
        List<BackendRuntime> backends = List.of(
                Backends.backend("a", 5),
                Backends.backend("b", 5),
                Backends.backend("c", 1));
        Balancer rr = new RoundRobinBalancer(backends);

        String previous = null;
        int run = 0;
        int longestRun = 0;
        for (int i = 0; i < 110; i++) {   // 10 full periods, so wraparound counts
            String id = Backends.idOf(rr.pick(""));
            run = id.equals(previous) ? run + 1 : 1;
            previous = id;
            longestRun = Math.max(longestRun, run);
        }
        assertTrue(longestRun <= 2,
                "longest run was " + longestRun + "; naive weighted RR would give 5");
    }

    @Test
    void weightedRoundRobinReducesTheScheduleByGcd() {
        RoundRobinBalancer rr = new RoundRobinBalancer(List.of(
                Backends.backend("a", 100),
                Backends.backend("b", 100),
                Backends.backend("c", 100)));

        assertEquals(3, rr.schedule().length,
                "equal weights must cost one slot each, not one hundred");
    }

    @Test
    void weightedRoundRobinSkipsUnhealthyWithoutLosingWeighting() {
        List<BackendRuntime> backends = List.of(
                Backends.backend("a", 3),
                Backends.backend("b", 1));
        Backends.markUnhealthy(backends.get(1));
        Balancer rr = new RoundRobinBalancer(backends);

        for (int i = 0; i < 40; i++) {
            assertEquals("a", Backends.idOf(rr.pick("")));
        }
    }

    // -------------------------------------------------- least connections

    @Test
    void leastConnectionsPicksTheLeastLoaded() {
        List<BackendRuntime> backends = Backends.equalWeight(3);
        Backends.setInflight(backends.get(0), 10);
        Backends.setInflight(backends.get(1), 2);
        Backends.setInflight(backends.get(2), 7);

        assertEquals("b1", Backends.idOf(new LeastConnectionsBalancer(backends).pick("")));
    }

    @Test
    void leastConnectionsIgnoresLoadOnUnhealthyBackends() {
        List<BackendRuntime> backends = Backends.equalWeight(2);
        Backends.setInflight(backends.get(0), 50);
        Backends.markUnhealthy(backends.get(1));   // idle but ejected

        assertEquals("b0", Backends.idOf(new LeastConnectionsBalancer(backends).pick("")));
    }

    // ------------------------------------------------------------- p2c

    @Test
    void p2cSpreadsEvenlyAcrossIdleBackends() {
        List<BackendRuntime> backends = Backends.equalWeight(3);
        Balancer p2c = new P2CBalancer(backends);

        Map<String, Integer> hits = new HashMap<>();
        int picks = 30_000;
        for (int i = 0; i < picks; i++) {
            hits.merge(Backends.idOf(p2c.pick("")), 1, Integer::sum);
        }
        int expected = picks / 3;
        hits.forEach((id, n) -> assertTrue(Math.abs(n - expected) < expected * 0.10,
                id + " got " + n + ", expected within 10% of " + expected));
    }

    @Test
    void p2cAvoidsTheLoadedBackend() {
        List<BackendRuntime> backends = Backends.equalWeight(3);
        Backends.setInflight(backends.get(0), 100);
        Balancer p2c = new P2CBalancer(backends);

        int loaded = 0;
        for (int i = 0; i < 10_000; i++) {
            if (Backends.idOf(p2c.pick("")).equals("b0")) {
                loaded++;
            }
        }
        // Reached only when both random probes land on it: ~1/9 of the time.
        assertTrue(loaded < 10_000 * 0.20,
                "loaded backend took " + loaded + "/10000; two-choice should hold it near 1/9");
    }

    /**
     * MEA-004 groundwork: the variance difference between P2C and naive
     * least-connections under concurrent selection is the graph worth publishing.
     * Here we assert only the property that makes it possible — P2C reads no
     * shared minimum, so two selectors rarely agree.
     */
    @Test
    void p2cDoesNotAlwaysAgreeWithItselfOnTheSameState() {
        List<BackendRuntime> backends = Backends.equalWeight(4);
        Balancer p2c = new P2CBalancer(backends);

        String first = Backends.idOf(p2c.pick(""));
        boolean sawDifferent = false;
        for (int i = 0; i < 100 && !sawDifferent; i++) {
            sawDifferent = !Backends.idOf(p2c.pick("")).equals(first);
        }
        assertTrue(sawDifferent,
                "identical state always yielding the same choice is the herd behaviour p2c exists to avoid");
    }
}
