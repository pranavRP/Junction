package io.junction.config;

import java.util.List;

/**
 * A named group of interchangeable backends.
 *
 * <p>Carries only knobs whose behaviour actually ships. Admission control and
 * shedding settings (architecture.md §7) arrive with the phases that implement
 * them, so a key present in config always does something.
 *
 * @param hashKey      selector for {@link Strategy#CONSISTENT_HASH}, e.g.
 *                     {@code header:X-Session-Id}; empty for every other strategy
 * @param panicPercent below this percent of available backends the pool ignores
 *                     health and spreads across everything (FR-3.6); 0 disables
 * @param slowStartMs  ramp applied to a backend re-admitted by probes (FR-3.5);
 *                     0 disables and re-admission is immediate
 */
public record PoolConfig(
        String name,
        Strategy strategy,
        String hashKey,
        int panicPercent,
        long slowStartMs,
        HealthConfig health,
        BreakerConfig breaker,
        RetryConfig retry,
        UpstreamPoolConfig pool,
        List<BackendConfig> backends) {

    public PoolConfig {
        backends = List.copyOf(backends);
    }

    /** Convenience for tests and single-pool setups that want stock behaviour. */
    public static PoolConfig of(String name, List<BackendConfig> backends) {
        return new PoolConfig(name, Strategy.P2C, "", defaultPanicPercent(), 0,
                HealthConfig.defaults(), BreakerConfig.defaults(), RetryConfig.defaults(),
                UpstreamPoolConfig.defaults(), backends);
    }

    /**
     * Envoy's default, and defensible: below half the pool, the survivors would
     * take more than double their share and fall over in turn. Spreading across
     * everything gives the requests that would have been shed a chance, and the
     * ones that would have killed the healthy remainder somewhere else to go.
     */
    public static int defaultPanicPercent() {
        return 50;
    }
}
