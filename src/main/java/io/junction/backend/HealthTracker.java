package io.junction.backend;

import io.junction.config.HealthConfig;

import java.time.Clock;

/**
 * The health state machine for one backend (architecture.md §5).
 *
 * <p><b>Threading.</b> Transitions are driven exclusively by the control-plane
 * executor — never from an event loop (R-3). The data path only reads
 * {@link #state()}, which is why that one field is {@code volatile} and nothing
 * else needs to be: single writer, many readers, immutable value published.
 *
 * <p><b>Asymmetric thresholds are the point.</b> Ejecting takes
 * {@code unhealthyThreshold} consecutive failures so one unlucky probe cannot
 * remove a healthy backend; re-admitting takes {@code healthyThreshold}
 * consecutive successes so one lucky probe cannot restore a broken one. A single
 * counter with one threshold would make both mistakes.
 *
 * <p>Phase 3 inserts {@code SlowStart} between {@link HealthState.Unhealthy} and
 * {@link HealthState.Healthy}; the sealed switch below will fail to compile until
 * that transition is handled, which is the intent.
 */
public final class HealthTracker {

    private final int healthyThreshold;
    private final int unhealthyThreshold;
    private final Clock clock;

    /** Written by the control plane only; read from event loops (R-3). */
    private volatile HealthState state;

    private int consecutiveOk;
    private int consecutiveFailures;

    public HealthTracker(HealthConfig config, Clock clock) {
        this(config.healthyThreshold(), config.unhealthyThreshold(), clock);
    }

    public HealthTracker(int healthyThreshold, int unhealthyThreshold, Clock clock) {
        this.healthyThreshold = healthyThreshold;
        this.unhealthyThreshold = unhealthyThreshold;
        this.clock = clock;
        // Start healthy: a backend named in config is assumed good until probes
        // say otherwise. Starting unhealthy would blackhole all traffic for the
        // first probe interval on every deploy.
        this.state = new HealthState.Healthy(clock.millis());
    }

    public HealthState state() {
        return state;
    }

    public int consecutiveOk() {
        return consecutiveOk;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Applies one event and returns the resulting state.
     *
     * @return the new state, which is identical to the old one for the many
     *         combinations that are legitimately no-ops
     */
    public HealthState apply(HealthEvent event) {
        HealthState current = state;

        // Removed is terminal. Stop before touching counters so a probe that
        // races a removal cannot resurrect anything.
        if (current instanceof HealthState.Removed) {
            return current;
        }

        updateCounters(event);
        HealthState next = transition(current, event);
        if (next != current) {
            state = next;
        }
        return next;
    }

    private void updateCounters(HealthEvent event) {
        switch (event) {
            case HealthEvent.ProbeSucceeded ignored -> {
                consecutiveOk++;
                consecutiveFailures = 0;
            }
            case HealthEvent.ProbeFailed ignored -> {
                consecutiveFailures++;
                consecutiveOk = 0;
            }
            // Drain and inflight events say nothing about backend health, so
            // they must not disturb a probe streak in progress.
            case HealthEvent.DrainRequested ignored -> { }
            case HealthEvent.InflightDrained ignored -> { }
        }
    }

    private HealthState transition(HealthState current, HealthEvent event) {
        return switch (current) {
            case HealthState.Healthy healthy -> switch (event) {
                case HealthEvent.ProbeFailed failed ->
                        consecutiveFailures >= unhealthyThreshold
                                ? new HealthState.Unhealthy(now(), failed.reason(), consecutiveFailures)
                                : healthy;
                case HealthEvent.DrainRequested ignored -> new HealthState.Draining(now());
                case HealthEvent.ProbeSucceeded ignored -> healthy;
                // Not draining, so hitting zero in-flight is just an idle backend.
                case HealthEvent.InflightDrained ignored -> healthy;
            };

            case HealthState.Unhealthy unhealthy -> switch (event) {
                case HealthEvent.ProbeSucceeded ignored ->
                        consecutiveOk >= healthyThreshold
                                ? new HealthState.Healthy(now())
                                : unhealthy;
                // Refresh the failure count so operators can see how deep it is,
                // without restarting the clock on when it went bad.
                case HealthEvent.ProbeFailed failed -> new HealthState.Unhealthy(
                        unhealthy.since(), failed.reason(), consecutiveFailures);
                case HealthEvent.DrainRequested ignored -> new HealthState.Draining(now());
                case HealthEvent.InflightDrained ignored -> unhealthy;
            };

            case HealthState.Draining draining -> switch (event) {
                case HealthEvent.InflightDrained ignored -> new HealthState.Removed(now());
                // A draining backend is leaving on purpose. Probe results must not
                // pull it back into rotation mid-drain — that would defeat the
                // entire point of asking it to drain.
                case HealthEvent.ProbeSucceeded ignored -> draining;
                case HealthEvent.ProbeFailed ignored -> draining;
                case HealthEvent.DrainRequested ignored -> draining;
            };

            case HealthState.Removed removed -> removed; // unreachable; terminal
        };
    }

    private long now() {
        return clock.millis();
    }
}
