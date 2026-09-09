package io.junction.backend;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Authoritative health of one backend (architecture.md §5).
 *
 * <p>Sealed so every consumer must handle every state, and so adding a state
 * later is a compile error at each decision point rather than a silent
 * fallthrough. That is exactly what happened in Phase 3: inserting
 * {@link SlowStart} between {@link Unhealthy} and {@link Healthy} broke every
 * switch in the tracker and the exhaustive transition table, which is the design
 * working rather than the design being annoying.
 *
 * <p>{@link #acceptsTraffic()} and {@link #acceptsTrafficInPanic()} are declared
 * on the interface rather than computed with an {@code instanceof} chain at the
 * call site for the same reason: a new state cannot be added without deciding
 * whether it takes traffic, in normal operation and in panic.
 *
 * <p>Instances are immutable values. Transitions produce a new instance rather
 * than mutating, so the data path can read a state reference without tearing.
 */
public sealed interface HealthState {

    /** Wall-clock millis at which the backend entered this state. */
    long since();

    /** Whether the balancer may route new requests here. */
    boolean acceptsTraffic();

    /**
     * Whether the balancer may route here when the pool is in panic (FR-3.6).
     *
     * <p>Panic ignores <em>health</em>, not <em>intent</em>. A backend that failed
     * its probes might still serve something, so it comes back into rotation. A
     * backend that was deliberately drained or removed does not — an operator
     * taking a host out for maintenance did not ask for it to be conscripted the
     * moment the pool gets busy.
     */
    boolean acceptsTrafficInPanic();

    /** Short stable token for metrics and logs (R-33 closed enum). */
    String label();

    /** Passing probes; eligible for selection. */
    record Healthy(long since) implements HealthState {
        @Override public boolean acceptsTraffic() {
            return true;
        }

        @Override public boolean acceptsTrafficInPanic() {
            return true;
        }

        @Override public String label() {
            return "healthy";
        }
    }

    /**
     * Re-admitted by probes, but ramping (FR-3.5).
     *
     * <p><b>Why a cold backend cannot take its full share immediately.</b> It has
     * an empty connection pool, a cold JIT, an empty page cache and an empty
     * application cache. Handing it 1/N of production traffic the instant it
     * passes one probe is how a recovering backend gets knocked straight back
     * down, and how a rolling restart turns into an outage.
     *
     * <p><b>The ramp is on admission probability, not on weight.</b> All four
     * strategies already route selection through
     * {@link BackendRuntime#selectable()}, so declining a fraction of picks ramps
     * every strategy at once with no balancer changes. Weight ramping would mean
     * rebuilding the round-robin schedule and the hash ring mid-flight, for the
     * same aggregate effect.
     */
    record SlowStart(long since, long rampMs) implements HealthState {

        /** Never zero, or a backend with a long ramp would take nothing at all for a while. */
        private static final double MIN_SHARE = 0.05;

        @Override public boolean acceptsTraffic() {
            return true;
        }

        @Override public boolean acceptsTrafficInPanic() {
            return true;
        }

        @Override public String label() {
            return "slow_start";
        }

        /** Fraction of a normal share this backend should take at {@code nowMs}. */
        public double share(long nowMs) {
            if (rampMs <= 0) {
                return 1.0;
            }
            double elapsed = (double) (nowMs - since) / rampMs;
            return Math.min(1.0, Math.max(MIN_SHARE, elapsed));
        }

        /** True for {@link #share} of calls; the ramp itself. */
        boolean admits(long nowMs) {
            double share = share(nowMs);
            return share >= 1.0 || ThreadLocalRandom.current().nextDouble() < share;
        }

        boolean rampComplete(long nowMs) {
            return nowMs - since >= rampMs;
        }
    }

    /**
     * Failed enough consecutive probes to be ejected. Still probed, so it can
     * come back — ejection is not deletion.
     */
    record Unhealthy(long since, String reason, int consecutiveFailures) implements HealthState {
        @Override public boolean acceptsTraffic() {
            return false;
        }

        @Override public boolean acceptsTrafficInPanic() {
            return true;
        }

        @Override public String label() {
            return "unhealthy";
        }
    }

    /**
     * Taking no new requests but finishing the ones it has. Reached by weight 0
     * or an admin drain, never by a probe result.
     */
    record Draining(long since) implements HealthState {
        @Override public boolean acceptsTraffic() {
            return false;
        }

        @Override public boolean acceptsTrafficInPanic() {
            return false;
        }

        @Override public String label() {
            return "draining";
        }
    }

    /** Terminal. Drained to zero in-flight and out of rotation for good. */
    record Removed(long since) implements HealthState {
        @Override public boolean acceptsTraffic() {
            return false;
        }

        @Override public boolean acceptsTrafficInPanic() {
            return false;
        }

        @Override public String label() {
            return "removed";
        }
    }
}
