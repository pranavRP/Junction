package io.junction.config;

/**
 * Active health probe settings for a pool (FR-3.1).
 *
 * @param path                probe target, e.g. {@code /healthz}
 * @param intervalMs          time between probes for one backend
 * @param timeoutMs           a probe exceeding this counts as a failure
 * @param healthyThreshold    consecutive passes before an unhealthy backend returns
 * @param unhealthyThreshold  consecutive failures before a healthy backend is ejected
 */
public record HealthConfig(
        String path,
        long intervalMs,
        long timeoutMs,
        int healthyThreshold,
        int unhealthyThreshold) {

    public static HealthConfig defaults() {
        return new HealthConfig("/healthz", 2_000, 500, 2, 3);
    }

    /**
     * Asymmetric thresholds are deliberate: eject slowly enough to ride out one
     * bad probe, re-admit slowly enough not to trust a single lucky one.
     */
    public long ejectionLatencyMs() {
        return intervalMs * unhealthyThreshold;
    }
}
