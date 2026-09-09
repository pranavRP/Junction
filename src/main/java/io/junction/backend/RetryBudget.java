package io.junction.backend;

import io.junction.config.RetryConfig;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Bounds retries as a fraction of request volume (FR-3.4).
 *
 * <p><b>The failure this exists to prevent.</b> Retries are individually
 * reasonable and collectively catastrophic. Every backend down means every
 * request fails, every failure retries, and upstream volume multiplies by the
 * attempt count precisely when the backends can least afford it — the proxy
 * finishes off whatever the outage started. A per-request attempt limit does not
 * help, because the limit is per request and the problem is aggregate.
 *
 * <p><b>Shape:</b> a ring of one-second buckets over a fixed window. Retries are
 * allowed while they stay under {@code max(minPerSecond × window, requests ×
 * percent)}. The floor is what lets a pool serving three requests a minute retry
 * at all; the percentage is what caps a pool serving thirty thousand.
 *
 * <p><b>ponytail: approximate accounting on purpose.</b> Buckets are reset
 * lock-free, so a rollover racing an increment can lose or double-count an event
 * or two. This decides whether to send one more request during an outage, not
 * what to bill anyone — a lock on the request path to make it exact would cost
 * more than the error. Move to per-loop counters summed on read if the atomics
 * ever show up in a profile.
 */
public final class RetryBudget {

    private static final int WINDOW_SECONDS = 10;

    private final RetryConfig config;
    private final Clock clock;

    private final AtomicLongArray requests = new AtomicLongArray(WINDOW_SECONDS);
    private final AtomicLongArray retries = new AtomicLongArray(WINDOW_SECONDS);
    /** Which second each slot currently holds, so a stale slot is reset, not read. */
    private final AtomicLongArray epoch = new AtomicLongArray(WINDOW_SECONDS);

    public RetryBudget(RetryConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            epoch.set(i, Long.MIN_VALUE);
        }
    }

    /** Called once per client request, before its first attempt. */
    public void recordRequest() {
        if (!config.enabled()) {
            return;
        }
        long second = nowSeconds();
        int slot = slotFor(second);
        rollIfStale(slot, second);
        requests.incrementAndGet(slot);
    }

    /**
     * Spends one retry if the budget allows.
     *
     * @return true if the caller may retry; false means the request must fail now
     */
    public boolean tryRetry() {
        if (!config.enabled()) {
            return false;
        }
        long second = nowSeconds();
        int slot = slotFor(second);
        rollIfStale(slot, second);

        long spent = sum(retries, second);
        if (spent >= allowance(second)) {
            return false;
        }
        retries.incrementAndGet(slot);
        return true;
    }

    /** Retries permitted right now across the whole window. */
    public long allowance(long second) {
        long fromVolume = sum(requests, second) * config.budgetPercent() / 100;
        long floor = (long) config.minPerSecond() * WINDOW_SECONDS;
        return Math.max(floor, fromVolume);
    }

    public long requestsInWindow() {
        return sum(requests, nowSeconds());
    }

    public long retriesInWindow() {
        return sum(retries, nowSeconds());
    }

    private long sum(AtomicLongArray counters, long second) {
        long total = 0;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            // Only slots inside the window count. Without this check the ring
            // would keep reporting a burst from a minute ago as current traffic.
            long age = second - epoch.get(i);
            if (age >= 0 && age < WINDOW_SECONDS) {
                total += counters.get(i);
            }
        }
        return total;
    }

    private void rollIfStale(int slot, long second) {
        if (epoch.get(slot) != second && epoch.getAndSet(slot, second) != second) {
            requests.set(slot, 0);
            retries.set(slot, 0);
        }
    }

    private static int slotFor(long second) {
        return (int) Math.floorMod(second, WINDOW_SECONDS);
    }

    private long nowSeconds() {
        return clock.millis() / 1_000L;
    }
}
