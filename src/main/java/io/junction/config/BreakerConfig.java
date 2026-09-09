package io.junction.config;

/**
 * Per-backend circuit breaker settings (FR-3.2, FR-3.3).
 *
 * <p>This is passive outlier detection and circuit breaking in one component,
 * deliberately. They are the same mechanism read two ways: count data-path
 * failures per backend, and stop sending to the ones that are failing. Building
 * them separately would mean two counters, two thresholds, and two answers to
 * "why is this backend out of rotation".
 *
 * <p><b>Why it exists at all when active probes already eject.</b> MEA-012: an
 * active probe cannot react faster than its own interval, which left 76 client
 * errors in the ejection window. The breaker sees real traffic, so it reacts in
 * requests rather than in probe intervals.
 *
 * @param consecutiveFailures failures in a row before the breaker opens; 0 disables
 * @param baseCooldownMs      first cooldown; doubles on each re-open
 * @param maxCooldownMs       ceiling for the doubling
 * @param halfOpenProbes      trial requests admitted per cooldown expiry
 */
public record BreakerConfig(
        int consecutiveFailures,
        long baseCooldownMs,
        long maxCooldownMs,
        int halfOpenProbes) {

    public static BreakerConfig defaults() {
        return new BreakerConfig(5, 1_000, 30_000, 1);
    }

    public static BreakerConfig disabled() {
        return new BreakerConfig(0, 1_000, 30_000, 1);
    }

    public boolean enabled() {
        return consecutiveFailures > 0;
    }
}
