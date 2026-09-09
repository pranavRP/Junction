package io.junction.config;

/**
 * Retry policy for a pool (FR-3.4).
 *
 * <p><b>The budget is the whole point.</b> A retry count alone turns a total
 * backend outage into a self-inflicted DDoS: every request fails, every failure
 * retries, and upstream volume multiplies by the attempt count at exactly the
 * moment the backends can least afford it. The budget caps retries as a fraction
 * of request volume, so amplification is bounded no matter how bad it gets.
 *
 * <p>Expressed as a percent rather than a ratio so the config stays integral —
 * the loader rejects anything that would need silent truncation, and a
 * {@code 0.1} that arrives as a float is exactly the kind of value that would.
 *
 * @param maxAttempts   total attempts per request including the first; 1 disables retries
 * @param budgetPercent retries allowed as a percent of requests in the window
 * @param minPerSecond  floor so a low-traffic pool can still retry at all
 */
public record RetryConfig(int maxAttempts, int budgetPercent, int minPerSecond) {

    public static RetryConfig defaults() {
        return new RetryConfig(2, 10, 3);
    }

    public static RetryConfig disabled() {
        return new RetryConfig(1, 0, 0);
    }

    public boolean enabled() {
        return maxAttempts > 1;
    }
}
