package io.junction.backend;

import io.junction.config.BreakerConfig;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-backend circuit breaker, fed by real request outcomes (FR-3.2, FR-3.3).
 *
 * <p>This is also the passive outlier detector. They are not two things: counting
 * data-path failures per backend and refusing to send to the ones that are
 * failing is one mechanism described from two directions. Two implementations
 * would mean two thresholds disagreeing about the same backend.
 *
 * <p><b>Threading.</b> Unlike {@link HealthTracker}, this is written from every
 * event loop that finishes a request, so it cannot use the single-writer volatile
 * trick and uses atomics instead. Nothing here blocks or allocates (R-3).
 * Concurrent updates can race by a small amount — two loops can both trip a
 * breaker that is already tripping, or a couple of extra trial requests can slip
 * through half-open. Both are bounded by concurrency and neither changes the
 * outcome, so this is approximate on purpose rather than locked.
 *
 * <p><b>Why exponential cooldown.</b> A fixed cooldown against a backend that is
 * genuinely dead means a trial request every cooldown forever — a slow, permanent
 * drip of doomed traffic and doomed connections. Doubling to a ceiling means a
 * brief blip costs one short pause and a real outage costs almost nothing.
 */
public final class CircuitBreaker {

    /** Cap the shift, not just the result: {@code 1L << 64} is 1, not infinity. */
    private static final int MAX_SHIFT = 20;

    private final BreakerConfig config;
    private final Clock clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 0 while closed; otherwise the millis at which the breaker last opened. */
    private final AtomicLong openedAt = new AtomicLong();
    /** Opens since the last successful close — the exponent for the cooldown. */
    private final AtomicInteger trips = new AtomicInteger();
    /** Trial requests admitted since the current cooldown expired. */
    private final AtomicInteger halfOpenAdmitted = new AtomicInteger();

    public CircuitBreaker(BreakerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * Whether this backend is currently barred from selection.
     *
     * <p><b>Deliberately free of side effects.</b> Every balancer calls
     * {@code selectable()} several times per pick while scanning, so consuming a
     * half-open permit here would burn the whole trial allowance on one request
     * that might not even choose this backend. The permit is taken in
     * {@link #onAdmitted()} instead, once the pick is committed.
     */
    public boolean blocks() {
        if (!config.enabled()) {
            return false;
        }
        long opened = openedAt.get();
        if (opened == 0L) {
            return false;
        }
        if (clock.millis() - opened < cooldownMs()) {
            return true;
        }
        return halfOpenAdmitted.get() >= config.halfOpenProbes();
    }

    /** Called once per request actually routed here, to spend a half-open permit. */
    public void onAdmitted() {
        if (config.enabled() && openedAt.get() != 0L) {
            halfOpenAdmitted.incrementAndGet();
        }
    }

    public void recordSuccess() {
        if (!config.enabled()) {
            return;
        }
        consecutiveFailures.set(0);
        if (openedAt.getAndSet(0L) != 0L) {
            // A trial request came back clean. Reset the backoff too: the next
            // failure deserves a fresh short cooldown, not the depth of an
            // outage the backend has demonstrably recovered from.
            trips.set(0);
            halfOpenAdmitted.set(0);
        }
    }

    public void recordFailure() {
        if (!config.enabled()) {
            return;
        }
        long opened = openedAt.get();
        if (opened != 0L) {
            failedWhileOpen(opened);
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= config.consecutiveFailures()) {
            open(0L);
        }
    }

    /**
     * A failure arriving while open is either a trial request failing — reopen and
     * back off further — or a request that started before the breaker tripped and
     * is only now landing. The second must not deepen the backoff, or one burst of
     * concurrent failures would jump the cooldown several doublings at once.
     */
    private void failedWhileOpen(long opened) {
        if (clock.millis() - opened < cooldownMs()) {
            return;
        }
        open(opened);
    }

    private void open(long expected) {
        // CAS on the generation so concurrent trippers increment `trips` once
        // between them, not once each.
        if (openedAt.compareAndSet(expected, Math.max(1L, clock.millis()))) {
            trips.incrementAndGet();
            halfOpenAdmitted.set(0);
            consecutiveFailures.set(0);
        }
    }

    /** Current cooldown, doubling per consecutive trip up to the configured ceiling. */
    public long cooldownMs() {
        int shift = Math.min(MAX_SHIFT, Math.max(0, trips.get() - 1));
        long scaled = config.baseCooldownMs() << shift;
        return Math.min(config.maxCooldownMs(), scaled);
    }

    /** Closed-enum token for metrics and logs (R-33). */
    public String label() {
        long opened = openedAt.get();
        if (opened == 0L) {
            return "closed";
        }
        return clock.millis() - opened < cooldownMs() ? "open" : "half_open";
    }

    public int trips() {
        return trips.get();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
