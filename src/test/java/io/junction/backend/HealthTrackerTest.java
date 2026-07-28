package io.junction.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthTrackerTest {

    private static final long T0 = 1_000_000L;

    private static MutableClock clock() {
        return new MutableClock(T0);
    }

    /** Thresholds of 1 so a single probe flips state and the table stays readable. */
    private static HealthTracker trackerAt(HealthState target, MutableClock clock) {
        HealthTracker t = new HealthTracker(1, 1, clock);
        switch (target.label()) {
            case "healthy" -> { /* initial state */ }
            case "unhealthy" -> t.apply(new HealthEvent.ProbeFailed("seed"));
            case "draining" -> t.apply(new HealthEvent.DrainRequested());
            case "removed" -> {
                t.apply(new HealthEvent.DrainRequested());
                t.apply(new HealthEvent.InflightDrained());
            }
            default -> throw new IllegalArgumentException(target.label());
        }
        assertEquals(target.label(), t.state().label(), "failed to seed the starting state");
        return t;
    }

    private static final HealthState HEALTHY = new HealthState.Healthy(0);
    private static final HealthState UNHEALTHY = new HealthState.Unhealthy(0, "seed", 1);
    private static final HealthState DRAINING = new HealthState.Draining(0);
    private static final HealthState REMOVED = new HealthState.Removed(0);

    private static final HealthEvent OK = new HealthEvent.ProbeSucceeded();
    private static final HealthEvent FAIL = new HealthEvent.ProbeFailed("probe failed");
    private static final HealthEvent DRAIN = new HealthEvent.DrainRequested();
    private static final HealthEvent EMPTY = new HealthEvent.InflightDrained();

    /**
     * R-21: every state crossed with every event, including the combinations that
     * are illegal or meaningless. The no-op rows are the ones that matter — they
     * are where an unconsidered transition would otherwise hide.
     */
    static Stream<Arguments> transitionTable() {
        return Stream.of(
                // from        event   expected     why
                Arguments.of(HEALTHY, OK, "healthy", "a passing probe changes nothing"),
                Arguments.of(HEALTHY, FAIL, "unhealthy", "threshold 1, so one failure ejects"),
                Arguments.of(HEALTHY, DRAIN, "draining", "operator or weight 0"),
                Arguments.of(HEALTHY, EMPTY, "healthy", "idle is not draining"),

                Arguments.of(UNHEALTHY, OK, "healthy", "threshold 1, so one pass re-admits"),
                Arguments.of(UNHEALTHY, FAIL, "unhealthy", "stays out, failure count deepens"),
                Arguments.of(UNHEALTHY, DRAIN, "draining", "an ejected backend can still be drained"),
                Arguments.of(UNHEALTHY, EMPTY, "unhealthy", "not draining, so irrelevant"),

                Arguments.of(DRAINING, OK, "draining", "probes must not revive a deliberate drain"),
                Arguments.of(DRAINING, FAIL, "draining", "already leaving; failure adds nothing"),
                Arguments.of(DRAINING, DRAIN, "draining", "draining twice is a no-op"),
                Arguments.of(DRAINING, EMPTY, "removed", "the only exit from draining"),

                Arguments.of(REMOVED, OK, "removed", "terminal"),
                Arguments.of(REMOVED, FAIL, "removed", "terminal"),
                Arguments.of(REMOVED, DRAIN, "removed", "terminal"),
                Arguments.of(REMOVED, EMPTY, "removed", "terminal"));
    }

    @ParameterizedTest(name = "{0} + {1} -> {2} ({3})")
    @MethodSource("transitionTable")
    void exhaustiveTransitionTable(HealthState from, HealthEvent event, String expected, String why) {
        MutableClock clock = clock();
        HealthTracker tracker = trackerAt(from, clock);

        HealthState result = tracker.apply(event);

        assertEquals(expected, result.label(), why);
        assertEquals(expected, tracker.state().label(), "returned state must match stored state");
    }

    @Test
    void tableCoversEveryStateEventCombination() {
        List<Arguments> rows = transitionTable().toList();
        assertEquals(4 * 4, rows.size(),
                "the table must enumerate all states x events, or a transition is unconsidered");
    }

    // ------------------------------------------------------------- thresholds

    @Test
    void ejectionRequiresConsecutiveFailures() {
        HealthTracker t = new HealthTracker(2, 3, clock());

        assertTrue(t.apply(new HealthEvent.ProbeFailed("1")).acceptsTraffic(), "1 of 3");
        assertTrue(t.apply(new HealthEvent.ProbeFailed("2")).acceptsTraffic(), "2 of 3");
        assertFalse(t.apply(new HealthEvent.ProbeFailed("3")).acceptsTraffic(), "3 of 3 ejects");
    }

    /** One good probe must wipe the streak, or unrelated blips accumulate into an ejection. */
    @Test
    void oneSuccessResetsTheFailureStreak() {
        HealthTracker t = new HealthTracker(2, 3, clock());

        t.apply(new HealthEvent.ProbeFailed("1"));
        t.apply(new HealthEvent.ProbeFailed("2"));
        t.apply(new HealthEvent.ProbeSucceeded());
        assertEquals(0, t.consecutiveFailures());

        t.apply(new HealthEvent.ProbeFailed("1 again"));
        t.apply(new HealthEvent.ProbeFailed("2 again"));
        assertTrue(t.state().acceptsTraffic(), "streak restarted, so still 2 of 3");
    }

    @Test
    void readmissionRequiresConsecutiveSuccesses() {
        HealthTracker t = new HealthTracker(2, 1, clock());
        t.apply(new HealthEvent.ProbeFailed("down"));
        assertFalse(t.state().acceptsTraffic());

        assertFalse(t.apply(new HealthEvent.ProbeSucceeded()).acceptsTraffic(), "1 of 2");
        assertTrue(t.apply(new HealthEvent.ProbeSucceeded()).acceptsTraffic(), "2 of 2 re-admits");
    }

    @Test
    void oneFailureResetsTheRecoveryStreak() {
        HealthTracker t = new HealthTracker(3, 1, clock());
        t.apply(new HealthEvent.ProbeFailed("down"));

        t.apply(new HealthEvent.ProbeSucceeded());
        t.apply(new HealthEvent.ProbeSucceeded());
        t.apply(new HealthEvent.ProbeFailed("flapping"));
        assertEquals(0, t.consecutiveOk());

        t.apply(new HealthEvent.ProbeSucceeded());
        t.apply(new HealthEvent.ProbeSucceeded());
        assertFalse(t.state().acceptsTraffic(), "streak restarted, so still 2 of 3");
    }

    /** Drain and inflight events say nothing about health and must not disturb a streak. */
    @Test
    void nonProbeEventsDoNotDisturbProbeCounters() {
        HealthTracker t = new HealthTracker(2, 3, clock());
        t.apply(new HealthEvent.ProbeFailed("1"));
        t.apply(new HealthEvent.ProbeFailed("2"));

        t.apply(new HealthEvent.InflightDrained());

        assertEquals(2, t.consecutiveFailures(), "in-flight count is not a probe result");
        assertFalse(t.apply(new HealthEvent.ProbeFailed("3")).acceptsTraffic());
    }

    // ------------------------------------------------------------------ clock

    @Test
    void timestampsComeFromTheInjectedClock() {
        MutableClock clock = clock();
        HealthTracker t = new HealthTracker(1, 1, clock);
        assertEquals(T0, t.state().since());

        clock.advanceMillis(5_000);
        HealthState ejected = t.apply(new HealthEvent.ProbeFailed("down"));
        assertEquals(T0 + 5_000, ejected.since());

        clock.advanceMillis(1_000);
        assertEquals(T0 + 6_000, t.apply(new HealthEvent.ProbeSucceeded()).since());
    }

    /**
     * Deepening failures must not restart the clock, or "how long has this been
     * broken" becomes unanswerable from the state alone.
     */
    @Test
    void repeatedFailuresPreserveTheOriginalEjectionTime() {
        MutableClock clock = clock();
        HealthTracker t = new HealthTracker(1, 1, clock);
        long ejectedAt = t.apply(new HealthEvent.ProbeFailed("first")).since();

        clock.advanceMillis(10_000);
        HealthState later = t.apply(new HealthEvent.ProbeFailed("second"));

        assertEquals(ejectedAt, later.since(), "ejection time must not move");
        assertInstanceOf(HealthState.Unhealthy.class, later);
        assertEquals(2, ((HealthState.Unhealthy) later).consecutiveFailures());
        assertEquals("second", ((HealthState.Unhealthy) later).reason(), "latest reason wins");
    }

    // --------------------------------------------------------------- identity

    @Test
    void noOpTransitionsReturnTheIdenticalInstance() {
        HealthTracker t = new HealthTracker(2, 3, clock());
        HealthState before = t.state();

        assertSame(before, t.apply(new HealthEvent.ProbeSucceeded()),
                "a no-op must not allocate a new state, or listeners see phantom transitions");
    }

    @Test
    void removedStaysRemovedAndIgnoresLaterProbes() {
        HealthTracker t = new HealthTracker(1, 1, clock());
        t.apply(new HealthEvent.DrainRequested());
        t.apply(new HealthEvent.InflightDrained());

        assertInstanceOf(HealthState.Removed.class, t.state());
        t.apply(new HealthEvent.ProbeSucceeded());
        t.apply(new HealthEvent.ProbeSucceeded());

        assertInstanceOf(HealthState.Removed.class, t.state(), "removal is permanent");
        assertEquals(0, t.consecutiveOk(), "a terminal tracker must not even count");
    }

    @Test
    void onlyHealthyAcceptsTraffic() {
        assertTrue(HEALTHY.acceptsTraffic());
        assertFalse(UNHEALTHY.acceptsTraffic());
        assertFalse(DRAINING.acceptsTraffic(), "draining finishes in-flight but takes nothing new");
        assertFalse(REMOVED.acceptsTraffic());
    }
}
