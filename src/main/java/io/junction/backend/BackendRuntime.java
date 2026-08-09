package io.junction.backend;

import io.junction.config.BackendConfig;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable per-backend runtime state (design.md §1).
 *
 * <p>Two categories of field with different threading rules. Health is written
 * only by the control plane and read by event loops, which {@link HealthTracker}
 * handles with a single volatile. In-flight is written by many event loops on the
 * data path, so it is an atomic — a counter increment never parks a thread, so
 * this is not the blocking that R-3 forbids.
 *
 * <p>Breaker, EWMA latency and slow-start weight ramping (design.md §1) belong to
 * Phase 3 and are absent rather than stubbed.
 */
public final class BackendRuntime {

    private final BackendConfig config;
    private final HealthTracker health;
    private final AtomicInteger inflight = new AtomicInteger();

    public BackendRuntime(BackendConfig config, HealthTracker health) {
        this.config = config;
        this.health = health;
        // FR-2.2: weight 0 means drain. Expressed as a state-machine event rather
        // than a special case at every selection site, so "why is this backend not
        // receiving traffic" has exactly one answer to look up.
        if (config.weight() == 0) {
            health.apply(new HealthEvent.DrainRequested());
        }
    }

    public String id() {
        return config.id();
    }

    public String host() {
        return config.host();
    }

    public int port() {
        return config.port();
    }

    public int weight() {
        return config.weight();
    }

    public BackendConfig config() {
        return config;
    }

    public HealthTracker healthTracker() {
        return health;
    }

    public HealthState health() {
        return health.state();
    }

    /** Whether the balancer may route a new request here right now. */
    public boolean selectable() {
        return health.state().acceptsTraffic();
    }

    public int inflight() {
        return inflight.get();
    }

    public void requestStarted() {
        inflight.incrementAndGet();
    }

    /**
     * Must be called from a {@code finally} (R-6). A leaked in-flight count is
     * permanent: least-connections and P2C would route away from this backend
     * for the lifetime of the process.
     */
    public void requestFinished() {
        int now = inflight.decrementAndGet();
        if (now < 0) {
            // Clamp rather than trust the counter: a negative value would make
            // this backend permanently the most attractive choice.
            inflight.compareAndSet(now, 0);
        }
    }

    @Override
    public String toString() {
        return "Backend[" + id() + " " + host() + ":" + port()
                + " w=" + weight() + " " + health().label() + " inflight=" + inflight() + "]";
    }
}
