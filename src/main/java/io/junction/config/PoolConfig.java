package io.junction.config;

import java.util.List;

/**
 * A named group of interchangeable backends.
 *
 * <p>Carries only knobs whose behaviour actually ships. Breaker, retry, outlier
 * and slow-start settings (architecture.md §7) arrive with the phases that
 * implement them, so a key present in config always does something.
 *
 * @param hashKey selector for {@link Strategy#CONSISTENT_HASH}, e.g.
 *                {@code header:X-Session-Id}; empty for every other strategy
 */
public record PoolConfig(
        String name,
        Strategy strategy,
        String hashKey,
        HealthConfig health,
        UpstreamPoolConfig pool,
        List<BackendConfig> backends) {

    public PoolConfig {
        backends = List.copyOf(backends);
    }

    /** Convenience for tests and single-pool setups that want stock behaviour. */
    public static PoolConfig of(String name, List<BackendConfig> backends) {
        return new PoolConfig(name, Strategy.P2C, "",
                HealthConfig.defaults(), UpstreamPoolConfig.defaults(), backends);
    }
}
