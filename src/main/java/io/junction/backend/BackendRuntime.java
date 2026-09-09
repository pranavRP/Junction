package io.junction.backend;

import io.junction.config.BackendConfig;
import io.junction.config.BreakerConfig;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-backend runtime state (design.md §1).
 *
 * <p>Three categories of field with different threading rules. Health is written
 * only by the control plane and read by event loops, which {@link HealthTracker}
 * handles with a single volatile. In-flight and the breaker are written by many
 * event loops on the data path, so they are atomics — a counter increment never
 * parks a thread, so this is not the blocking that R-3 forbids.
 *
 * <p><b>{@link #selectable()} is the single choke point for routing eligibility</b>,
 * and that is what made Phase 3 cheap. All four balancers already asked this one
 * question, so slow start, breaker state and panic mode all landed here instead
 * of four times over in the strategies.
 */
public final class BackendRuntime {

    private final BackendConfig config;
    private final HealthTracker health;
    private final CircuitBreaker breaker;
    /** Shared with every backend in the pool; owned and written by {@link BackendPool}. */
    private final AtomicBoolean panic;
    private final Clock clock;

    private final AtomicInteger inflight = new AtomicInteger();
    /** Every attempt routed here, retries included — the numerator for MEA-005. */
    private final AtomicLong attempts = new AtomicLong();

    /** Test and single-backend construction: no breaker, never in panic. */
    public BackendRuntime(BackendConfig config, HealthTracker health) {
        this(config, health, BreakerConfig.disabled(), new AtomicBoolean(), Clock.systemUTC());
    }

    public BackendRuntime(BackendConfig config,
                          HealthTracker health,
                          BreakerConfig breaker,
                          AtomicBoolean panic,
                          Clock clock) {
        this.config = config;
        this.health = health;
        this.breaker = new CircuitBreaker(breaker, clock);
        this.panic = panic;
        this.clock = clock;
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

    public CircuitBreaker breaker() {
        return breaker;
    }

    /**
     * Whether the balancer may route a new request here right now.
     *
     * <p>Called several times per pick while a strategy scans, so it must stay
     * cheap and free of side effects — the half-open permit is taken in
     * {@link #requestStarted()}, once a pick is committed.
     *
     * <p>Panic short-circuits both the breaker and the ramp. Once the pool has
     * decided the healthy remainder cannot carry the load, holding traffic back
     * from a backend that merely looks bad is no longer the conservative choice.
     */
    public boolean selectable() {
        HealthState state = health.state();
        if (panic.get()) {
            return state.acceptsTrafficInPanic();
        }
        if (!state.acceptsTraffic() || breaker.blocks()) {
            return false;
        }
        return !(state instanceof HealthState.SlowStart ramping) || ramping.admits(clock.millis());
    }

    /**
     * Eligibility ignoring panic and the ramp — the input to the panic decision
     * itself. Kept separate from {@link #selectable()} so panic cannot feed back
     * into its own trigger and latch the pool into it permanently.
     */
    public boolean available() {
        return health.state().acceptsTraffic() && !breaker.blocks();
    }

    public int inflight() {
        return inflight.get();
    }

    public long attempts() {
        return attempts.get();
    }

    public void requestStarted() {
        attempts.incrementAndGet();
        breaker.onAdmitted();
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

    /** A request this backend served correctly — closes a half-open breaker. */
    public void recordSuccess() {
        breaker.recordSuccess();
    }

    /**
     * A request this backend failed: refused the connection, dropped it, timed
     * out, or answered 5xx. This is the passive outlier signal — it comes from
     * production traffic, so it sees failures a probe on {@code /healthz} never
     * will, and it sees them in requests rather than in probe intervals.
     */
    public void recordFailure() {
        breaker.recordFailure();
    }

    @Override
    public String toString() {
        return "Backend[" + id() + " " + host() + ":" + port()
                + " w=" + weight() + " " + health().label() + "/" + breaker.label()
                + " inflight=" + inflight() + "]";
    }
}
