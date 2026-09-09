package io.junction.admit;

import java.util.concurrent.Semaphore;

/**
 * Caps concurrently in-flight requests and sheds the rest (FR-4.2).
 *
 * <p><b>Why a concurrency limit and not a rate limit.</b> Requests per second is
 * a number about the client; concurrent requests is a number about us. By
 * Little's law the in-flight count is exactly {@code throughput × latency}, so
 * one bound on it bounds both the queueing inside the process and the memory
 * every in-flight request holds — without anyone having to guess an RPS figure
 * that stops being true the moment a backend slows down.
 *
 * <p><b>There is deliberately no queue.</b> Over the limit the request is shed
 * immediately. A queue in front of an overloaded service converts a fast, honest
 * 503 into a slow 503 delivered to a client that gave up two seconds ago, and it
 * is the single most common way p99 explodes past the knee. Shedding flat is what
 * makes the Phase 4 gate — throughput flat, p99 under 2× at twice the knee —
 * achievable at all.
 *
 * <p>Shared across every worker EventLoop, hence a {@link Semaphore}: its
 * {@code tryAcquire} is a non-blocking CAS and never parks a thread, so this is
 * shared state without blocking (R-3), the same trade
 * {@code ConnectionLimitHandler} already makes.
 *
 * <p><b>Caller contract:</b> exactly one {@link #release()} per {@code true} from
 * {@link #tryAcquire()}. A stray release raises the ceiling permanently, so the
 * caller tracks whether it holds a permit rather than releasing hopefully.
 */
public final class AdmissionController {

    private final int limit;
    /** Null when the limit is disabled — then there is nothing to count. */
    private final Semaphore permits;

    public AdmissionController(int maxInFlight) {
        this.limit = maxInFlight;
        this.permits = maxInFlight > 0 ? new Semaphore(maxInFlight) : null;
    }

    /** @return true if the caller holds a permit and must later release it */
    public boolean tryAcquire() {
        return permits == null || permits.tryAcquire();
    }

    public void release() {
        if (permits != null) {
            permits.release();
        }
    }

    public int inFlight() {
        return permits == null ? 0 : limit - permits.availablePermits();
    }

    public int limit() {
        return limit;
    }

    public boolean enabled() {
        return permits != null;
    }
}
