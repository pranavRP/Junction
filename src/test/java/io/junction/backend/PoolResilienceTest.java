package io.junction.backend;

import io.junction.balance.PickResult;
import io.junction.config.BackendConfig;
import io.junction.config.BreakerConfig;
import io.junction.config.HealthConfig;
import io.junction.config.PoolConfig;
import io.junction.config.RetryConfig;
import io.junction.config.Strategy;
import io.junction.config.UpstreamPoolConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Panic mode and passive ejection at the level they actually take effect: a pool
 * choosing a backend. Both are implemented by changing what
 * {@link BackendRuntime#selectable()} answers, so this is where the behaviour is,
 * not in any one balancer.
 */
class PoolResilienceTest {

    private static final long T0 = 1_000_000L;
    private static final BreakerConfig BREAKER = new BreakerConfig(2, 1_000, 8_000, 1);

    private static PoolConfig config(int backends, int panicPercent, BreakerConfig breaker) {
        List<BackendConfig> list = new ArrayList<>();
        for (int i = 0; i < backends; i++) {
            list.add(new BackendConfig("b" + i, "127.0.0.1", 8000 + i, 100));
        }
        return new PoolConfig("api", Strategy.ROUND_ROBIN, "", panicPercent, 0,
                new HealthConfig("/healthz", 2_000, 500, 1, 1),
                breaker, RetryConfig.disabled(), UpstreamPoolConfig.defaults(), list);
    }

    private static void failProbes(BackendRuntime b) {
        b.healthTracker().apply(new HealthEvent.ProbeFailed("test"));
    }

    // ----------------------------------------------------------------- panic

    @Test
    void aPoolBelowThePanicThresholdRoutesToUnhealthyBackends() {
        BackendPool pool = BackendPool.create(config(4, 50, BreakerConfig.disabled()),
                new MutableClock(T0));

        failProbes(pool.backends().get(0));
        failProbes(pool.backends().get(1));
        assertInstanceOf(PickResult.Chosen.class, pool.pick(""), "2 of 4 healthy is exactly at 50%");
        assertFalse(pool.inPanic(), "at the threshold, not below it");

        failProbes(pool.backends().get(2));
        assertInstanceOf(PickResult.Chosen.class, pool.pick(""), "1 of 4 is below 50%");
        assertTrue(pool.inPanic());
    }

    @Test
    void panicSpreadsAcrossEveryBackendRatherThanCrushingTheSurvivor() {
        BackendPool pool = BackendPool.create(config(4, 50, BreakerConfig.disabled()),
                new MutableClock(T0));
        for (int i = 0; i < 3; i++) {
            failProbes(pool.backends().get(i));
        }

        Set<String> chosen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            chosen.add(((PickResult.Chosen) pool.pick("")).backend().id());
        }

        assertEquals(4, chosen.size(),
                "panic exists so the one survivor is not handed 100% of the load");
    }

    /**
     * With panic off, an entirely dead pool is a 503 with a reason label. Panic
     * trades that certainty for a chance, which is a policy decision and so has
     * to be switchable.
     */
    @Test
    void panicDisabledStillReportsNoHealthyBackend() {
        BackendPool pool = BackendPool.create(config(3, 0, BreakerConfig.disabled()),
                new MutableClock(T0));
        pool.backends().forEach(PoolResilienceTest::failProbes);

        assertInstanceOf(PickResult.NoneAvailable.class, pool.pick(""));
    }

    @Test
    void panicLiftsAsSoonAsEnoughBackendsRecover() {
        BackendPool pool = BackendPool.create(config(4, 50, BreakerConfig.disabled()),
                new MutableClock(T0));
        for (int i = 0; i < 3; i++) {
            failProbes(pool.backends().get(i));
        }
        pool.pick("");
        assertTrue(pool.inPanic());

        pool.backends().get(0).healthTracker().apply(new HealthEvent.ProbeSucceeded());
        pool.backends().get(1).healthTracker().apply(new HealthEvent.ProbeSucceeded());
        pool.pick("");

        assertFalse(pool.inPanic(), "panic must not latch, or the pool never leaves it");
    }

    // --------------------------------------------------------------- breaker

    @Test
    void aTrippedBreakerTakesABackendOutOfRotationWithoutAnyProbe() {
        BackendPool pool = BackendPool.create(config(2, 0, BREAKER), new MutableClock(T0));
        BackendRuntime bad = pool.backends().get(0);

        bad.recordFailure();
        bad.recordFailure();

        assertFalse(bad.selectable(), "the breaker ejects on traffic, not on probe intervals");
        assertTrue(bad.health().acceptsTraffic(), "and does it without touching probe health");
        for (int i = 0; i < 10; i++) {
            assertEquals("b1", ((PickResult.Chosen) pool.pick("")).backend().id());
        }
    }

    @Test
    void aBreakerOpenOnEveryBackendTriggersPanic() {
        BackendPool pool = BackendPool.create(config(2, 50, BREAKER), new MutableClock(T0));
        for (BackendRuntime b : pool.backends()) {
            b.recordFailure();
            b.recordFailure();
        }

        assertInstanceOf(PickResult.Chosen.class, pool.pick(""),
                "breaker-open backends must count towards panic, or a fully tripped pool blackholes");
        assertTrue(pool.inPanic());
    }

    @Test
    void aClosedBreakerBackendIsSelectedAgain() {
        MutableClock clock = new MutableClock(T0);
        BackendPool pool = BackendPool.create(config(2, 0, BREAKER), clock);
        BackendRuntime bad = pool.backends().get(0);
        bad.recordFailure();
        bad.recordFailure();
        assertFalse(bad.selectable());

        clock.advanceMillis(1_000);
        bad.requestStarted();          // the trial request
        bad.recordSuccess();
        bad.requestFinished();

        assertTrue(bad.selectable());
    }

    // ------------------------------------------------------------ slow start

    @Test
    void aRampingBackendTakesLessThanItsShare() {
        PoolConfig base = config(2, 0, BreakerConfig.disabled());
        PoolConfig ramped = new PoolConfig(base.name(), base.strategy(), base.hashKey(),
                base.panicPercent(), 10_000, base.health(), base.breaker(), base.retry(),
                base.pool(), base.backends());
        MutableClock clock = new MutableClock(T0);
        BackendPool pool = BackendPool.create(ramped, clock);

        BackendRuntime recovering = pool.backends().get(0);
        failProbes(recovering);
        recovering.healthTracker().apply(new HealthEvent.ProbeSucceeded());
        assertEquals("slow_start", recovering.health().label());

        int picks = 0;
        for (int i = 0; i < 2_000; i++) {
            if (((PickResult.Chosen) pool.pick("")).backend().id().equals("b0")) {
                picks++;
            }
        }

        // 5% share at t=0 against a healthy peer, so far below the ~1000 an even
        // split would give. Loose bound on purpose: the ramp is probabilistic, and
        // asserting a tight interval on a random process is how you get a flaky
        // test that everyone learns to re-run.
        assertTrue(picks < 400, "a cold backend took " + picks + " of 2000 picks");
        assertTrue(picks > 0, "and it must still get some traffic, or it can never warm up");
    }
}
