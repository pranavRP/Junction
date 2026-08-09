package io.junction.balance;

import io.junction.backend.BackendRuntime;
import io.junction.config.BackendConfig;
import io.junction.config.HealthConfig;
import io.junction.config.PoolConfig;
import io.junction.config.Strategy;
import io.junction.config.UpstreamPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviour every strategy must share, asserted against all four. */
class BalancerContractTest {

    private static Balancer balancerFor(Strategy strategy, List<BackendRuntime> backends) {
        PoolConfig cfg = new PoolConfig("api", strategy,
                strategy == Strategy.CONSISTENT_HASH ? "header:X-Session-Id" : "",
                HealthConfig.defaults(), UpstreamPoolConfig.defaults(),
                backends.stream().map(BackendRuntime::config).toList());
        return Balancers.create(cfg, backends);
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void neverPicksAnUnhealthyBackend(Strategy strategy) {
        List<BackendRuntime> backends = Backends.equalWeight(3);
        Backends.markUnhealthy(backends.get(1));
        Balancer b = balancerFor(strategy, backends);

        for (int i = 0; i < 500; i++) {
            assertTrue(!Backends.idOf(b.pick("key" + i)).equals("b1"),
                    strategy + " routed to an ejected backend");
        }
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void reportsNoneAvailableWhenEveryBackendIsDown(Strategy strategy) {
        List<BackendRuntime> backends = Backends.equalWeight(3);
        backends.forEach(Backends::markUnhealthy);
        Balancer b = balancerFor(strategy, backends);

        PickResult r = b.pick("k");
        assertInstanceOf(PickResult.NoneAvailable.class, r, strategy.toString());
        assertEquals("no_healthy_backend", ((PickResult.NoneAvailable) r).reason());
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void reportsEmptyPoolDistinctlyFromAllDown(Strategy strategy) {
        Balancer b = balancerFor(strategy, List.of());

        PickResult r = b.pick("k");
        assertInstanceOf(PickResult.NoneAvailable.class, r);
        assertEquals("empty_pool", ((PickResult.NoneAvailable) r).reason(),
                "an empty pool is a config error; a down pool is an incident — different reasons");
    }

    /** FR-2.2: weight 0 means drain, so it must never be selected. */
    @ParameterizedTest
    @EnumSource(Strategy.class)
    void weightZeroDrainsTheBackend(Strategy strategy) {
        List<BackendRuntime> backends = List.of(
                Backends.backend("keep", 100),
                Backends.backend("drained", 0));
        Balancer b = balancerFor(strategy, backends);

        for (int i = 0; i < 300; i++) {
            assertEquals("keep", Backends.idOf(b.pick("key" + i)),
                    strategy + " routed to a weight-0 backend");
        }
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void survivesASinglelBackendPool(Strategy strategy) {
        List<BackendRuntime> backends = List.of(Backends.backend("only", 100));
        Balancer b = balancerFor(strategy, backends);

        for (int i = 0; i < 50; i++) {
            assertEquals("only", Backends.idOf(b.pick("k" + i)));
        }
    }

    /**
     * A backend coming back must start receiving traffic again without a rebuild.
     *
     * <p>The two survivors carry load so the recovered backend is the genuinely
     * better choice. Without that, least-connections would keep returning the
     * first of three equally idle backends — correct behaviour, but it would
     * make this assertion about luck rather than about recovery.
     */
    @ParameterizedTest
    @EnumSource(Strategy.class)
    void recoveredBackendIsUsedAgain(Strategy strategy) {
        List<BackendRuntime> backends = Backends.equalWeight(3);
        BackendRuntime ejected = backends.get(2);
        Backends.markUnhealthy(ejected);
        Balancer b = balancerFor(strategy, backends);

        Backends.setInflight(backends.get(0), 3);
        Backends.setInflight(backends.get(1), 3);
        ejected.healthTracker().apply(new io.junction.backend.HealthEvent.ProbeSucceeded());

        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 900; i++) {
            hits.merge(Backends.idOf(b.pick("key" + i)), 1, Integer::sum);
        }
        assertTrue(hits.getOrDefault("b2", 0) > 0,
                strategy + " never returned to a recovered backend, got " + hits);
    }

    @Test
    void strategyNamesAreStableForMetrics() {
        List<BackendRuntime> backends = Backends.equalWeight(2);
        assertEquals("round_robin", balancerFor(Strategy.ROUND_ROBIN, backends).name());
        assertEquals("least_connections", balancerFor(Strategy.LEAST_CONNECTIONS, backends).name());
        assertEquals("p2c", balancerFor(Strategy.P2C, backends).name());
        assertEquals("consistent_hash", balancerFor(Strategy.CONSISTENT_HASH, backends).name());
    }

    /** Guards the BackendConfig plumbing the other tests rely on. */
    @Test
    void backendRuntimeExposesItsConfig() {
        BackendRuntime b = Backends.backend("x", 42);
        BackendConfig cfg = b.config();
        assertEquals("x", cfg.id());
        assertEquals(42, cfg.weight());
    }
}
