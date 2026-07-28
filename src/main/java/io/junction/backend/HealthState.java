package io.junction.backend;

/**
 * Authoritative health of one backend (architecture.md §5).
 *
 * <p>Sealed so every consumer must handle every state, and so adding a state
 * later is a compile error at each decision point rather than a silent fallthrough
 * — which is exactly what will happen in Phase 3 when {@code SlowStart} is
 * inserted between {@link Unhealthy} and {@link Healthy}.
 *
 * <p>{@link #acceptsTraffic()} is declared on the interface rather than computed
 * with an {@code instanceof} chain at the call site for the same reason: a new
 * state cannot be added without deciding whether it takes traffic.
 *
 * <p>Instances are immutable values. Transitions produce a new instance rather
 * than mutating, so the data path can read a state reference without tearing.
 */
public sealed interface HealthState {

    /** Wall-clock millis at which the backend entered this state. */
    long since();

    /** Whether the balancer may route new requests here. */
    boolean acceptsTraffic();

    /** Short stable token for metrics and logs (R-33 closed enum). */
    String label();

    /** Passing probes; eligible for selection. */
    record Healthy(long since) implements HealthState {
        @Override public boolean acceptsTraffic() {
            return true;
        }

        @Override public String label() {
            return "healthy";
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

        @Override public String label() {
            return "draining";
        }
    }

    /** Terminal. Drained to zero in-flight and out of rotation for good. */
    record Removed(long since) implements HealthState {
        @Override public boolean acceptsTraffic() {
            return false;
        }

        @Override public String label() {
            return "removed";
        }
    }
}
