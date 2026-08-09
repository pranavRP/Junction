package io.junction.balance;

import io.junction.backend.BackendRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashBalancerTest {

    private static List<BackendRuntime> pool(int n) {
        List<BackendRuntime> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Backends.backend("backend-" + i, 100));
        }
        return out;
    }

    @Test
    void sameKeyAlwaysLandsOnTheSameBackend() {
        Balancer ch = new ConsistentHashBalancer(pool(5));

        String first = Backends.idOf(ch.pick("session-abc"));
        for (int i = 0; i < 200; i++) {
            assertEquals(first, Backends.idOf(ch.pick("session-abc")), "affinity must be stable");
        }
    }

    @Test
    void differentKeysSpreadAcrossTheRing() {
        Balancer ch = new ConsistentHashBalancer(pool(5));

        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            hits.merge(Backends.idOf(ch.pick("key-" + i)), 1, Integer::sum);
        }
        assertEquals(5, hits.size(), "every backend must own part of the ring");

        // 160 vnodes each will not be perfectly even; ±35% of the mean is the
        // band that granularity actually delivers, and asserting tighter would
        // be asserting luck.
        int mean = 50_000 / 5;
        hits.forEach((id, n) -> assertTrue(Math.abs(n - mean) < mean * 0.35,
                id + " owns " + n + " keys, mean is " + mean + " — ring is too lumpy"));
    }

    /** A missing header must not funnel all anonymous traffic to one backend. */
    @Test
    void requestsWithoutAKeyAreSpreadNotStacked() {
        Balancer ch = new ConsistentHashBalancer(pool(4));

        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 4_000; i++) {
            hits.merge(Backends.idOf(ch.pick("")), 1, Integer::sum);
        }
        assertEquals(4, hits.size(), "keyless requests all landed on " + hits.size() + " backend(s)");
    }

    /**
     * Bounded load is the difference between affinity as an optimisation and
     * affinity as an outage: a hot key must yield once its owner is overloaded.
     */
    @Test
    void hotKeyOverflowsToAnotherBackendWhenOwnerIsOverloaded() {
        List<BackendRuntime> backends = pool(4);
        Balancer ch = new ConsistentHashBalancer(backends);

        String key = "hot-key";
        String owner = Backends.idOf(ch.pick(key));

        BackendRuntime ownerRuntime = backends.stream()
                .filter(b -> b.id().equals(owner)).findFirst().orElseThrow();
        Backends.setInflight(ownerRuntime, 100);

        assertNotEquals(owner, Backends.idOf(ch.pick(key)),
                "an overloaded owner must be skipped, not fed more of the same key");
    }

    @Test
    void affinityReturnsOnceLoadSubsides() {
        List<BackendRuntime> backends = pool(4);
        Balancer ch = new ConsistentHashBalancer(backends);

        String key = "sticky";
        String owner = Backends.idOf(ch.pick(key));
        BackendRuntime ownerRuntime = backends.stream()
                .filter(b -> b.id().equals(owner)).findFirst().orElseThrow();

        Backends.setInflight(ownerRuntime, 100);
        assertNotEquals(owner, Backends.idOf(ch.pick(key)));

        for (int i = 0; i < 100; i++) {
            ownerRuntime.requestFinished();
        }
        assertEquals(owner, Backends.idOf(ch.pick(key)), "affinity must come back");
    }

    /** A uniformly busy pool must still serve; the bound is relative, not absolute. */
    @Test
    void uniformlyBusyPoolStillServes() {
        List<BackendRuntime> backends = pool(3);
        backends.forEach(b -> Backends.setInflight(b, 50));
        Balancer ch = new ConsistentHashBalancer(backends);

        for (int i = 0; i < 100; i++) {
            assertTrue(ch.pick("key-" + i) instanceof PickResult.Chosen,
                    "a busy but healthy pool must not report NoneAvailable");
        }
    }

    /**
     * MEA-003 — the measurement Phase 2 owes.
     *
     * <p>Prediction recorded before running it: removing 1 of 5 backends should
     * remap that backend's share and nothing else, so ~1/N = 20%. The whole point
     * of consistent hashing is that the other 80% do not move; a modulo scheme
     * would remap roughly 80%.
     */
    @Test
    void rebalanceFractionWhenOneOfFiveBackendsLeaves() {
        int keyCount = 100_000;
        List<BackendRuntime> five = pool(5);
        Balancer before = new ConsistentHashBalancer(five);
        Balancer after = new ConsistentHashBalancer(five.subList(0, 4));

        int moved = 0;
        int movedOffSurvivors = 0;
        for (int i = 0; i < keyCount; i++) {
            String key = "user-" + i;
            String owner = Backends.idOf(before.pick(key));
            String newOwner = Backends.idOf(after.pick(key));
            if (!owner.equals(newOwner)) {
                moved++;
                if (!owner.equals("backend-4")) {
                    movedOffSurvivors++;
                }
            }
        }

        double movedPct = 100.0 * moved / keyCount;
        double strandedPct = 100.0 * movedOffSurvivors / keyCount;
        System.out.printf("[MEA-003] %d keys, 5 backends, removed 1: %.2f%% remapped "
                + "(predicted 20.00%%), %.2f%% moved off a surviving backend%n",
                keyCount, movedPct, strandedPct);

        assertEquals(0, movedOffSurvivors,
                "keys on surviving backends must not move — that is the entire guarantee");
        assertTrue(movedPct > 15.0 && movedPct < 25.0,
                "expected ~20% remapped, measured " + movedPct + "%");
    }

    @Test
    void addingABackendMovesOnlyItsOwnShare() {
        int keyCount = 20_000;
        List<BackendRuntime> four = pool(4);
        List<BackendRuntime> five = new ArrayList<>(four);
        five.add(Backends.backend("backend-4", 100));

        Balancer before = new ConsistentHashBalancer(four);
        Balancer after = new ConsistentHashBalancer(five);

        int moved = 0;
        for (int i = 0; i < keyCount; i++) {
            String key = "user-" + i;
            String oldOwner = Backends.idOf(before.pick(key));
            String newOwner = Backends.idOf(after.pick(key));
            if (!oldOwner.equals(newOwner)) {
                moved++;
                assertEquals("backend-4", newOwner,
                        "a key may only move onto the new backend, never between existing ones");
            }
        }
        double movedPct = 100.0 * moved / keyCount;
        assertTrue(movedPct > 12.0 && movedPct < 28.0,
                "expected ~1/5 to move onto the new node, measured " + movedPct + "%");
    }
}
