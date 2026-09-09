package io.junction.backend;

import io.junction.config.BreakerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private static final long T0 = 1_000_000L;

    /** Trip on 3, 1s base cooldown doubling to 8s, one trial per window. */
    private static final BreakerConfig CONFIG = new BreakerConfig(3, 1_000, 8_000, 1);

    private static CircuitBreaker breaker(MutableClock clock) {
        return new CircuitBreaker(CONFIG, clock);
    }

    @Test
    void staysClosedBelowTheThreshold() {
        CircuitBreaker b = breaker(new MutableClock(T0));

        b.recordFailure();
        b.recordFailure();

        assertFalse(b.blocks(), "2 of 3 is not a trip");
        assertEquals("closed", b.label());
    }

    @Test
    void opensOnConsecutiveFailures() {
        CircuitBreaker b = breaker(new MutableClock(T0));

        b.recordFailure();
        b.recordFailure();
        b.recordFailure();

        assertTrue(b.blocks(), "3 of 3 opens");
        assertEquals("open", b.label());
    }

    /**
     * The same reason {@link HealthTracker} resets its streak: unrelated blips
     * spread over hours must not accumulate into an ejection.
     */
    @Test
    void oneSuccessResetsTheFailureStreak() {
        CircuitBreaker b = breaker(new MutableClock(T0));

        b.recordFailure();
        b.recordFailure();
        b.recordSuccess();
        b.recordFailure();
        b.recordFailure();

        assertFalse(b.blocks(), "streak restarted, so still 2 of 3");
    }

    @Test
    void admitsExactlyTheConfiguredTrialsWhenTheCooldownExpires() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker b = breaker(clock);
        trip(b);

        clock.advanceMillis(999);
        assertTrue(b.blocks(), "cooldown has not expired");

        clock.advanceMillis(1);
        assertFalse(b.blocks(), "half-open: one trial is allowed through");
        assertEquals("half_open", b.label());

        b.onAdmitted();
        assertTrue(b.blocks(), "the single trial permit is spent");
    }

    @Test
    void aSuccessfulTrialClosesTheBreaker() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker b = breaker(clock);
        trip(b);
        clock.advanceMillis(1_000);

        b.onAdmitted();
        b.recordSuccess();

        assertFalse(b.blocks());
        assertEquals("closed", b.label());
        assertEquals(0, b.trips(), "a clean trial resets the backoff, not just the state");
    }

    @Test
    void aFailedTrialReopensWithADoubledCooldown() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker b = breaker(clock);
        trip(b);
        assertEquals(1_000, b.cooldownMs());

        clock.advanceMillis(1_000);
        b.onAdmitted();
        b.recordFailure();

        assertEquals(2_000, b.cooldownMs(), "second trip doubles");
        assertTrue(b.blocks());

        clock.advanceMillis(2_000);
        b.onAdmitted();
        b.recordFailure();
        assertEquals(4_000, b.cooldownMs(), "third trip doubles again");
    }

    @Test
    void theCooldownStopsAtItsCeiling() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker b = breaker(clock);
        trip(b);
        for (int i = 0; i < 10; i++) {
            clock.advanceMillis(b.cooldownMs());
            b.onAdmitted();
            b.recordFailure();
        }
        assertEquals(8_000, b.cooldownMs(), "doubling must saturate, not overflow past the ceiling");
    }

    /**
     * A request that started before the trip lands after it. Counting that as a
     * failed trial would let one burst of concurrent failures jump the cooldown
     * several doublings at once, turning a 1s pause into 30s.
     */
    @Test
    void failuresLandingDuringTheCooldownDoNotDeepenTheBackoff() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker b = breaker(clock);
        trip(b);

        clock.advanceMillis(500);
        b.recordFailure();
        b.recordFailure();
        b.recordFailure();

        assertEquals(1_000, b.cooldownMs(), "still the first trip");
        assertEquals(1, b.trips());
    }

    @Test
    void aDisabledBreakerNeverBlocks() {
        CircuitBreaker b = new CircuitBreaker(BreakerConfig.disabled(), new MutableClock(T0));

        for (int i = 0; i < 100; i++) {
            b.recordFailure();
        }

        assertFalse(b.blocks());
        assertEquals("closed", b.label());
    }

    private static void trip(CircuitBreaker b) {
        for (int i = 0; i < CONFIG.consecutiveFailures(); i++) {
            b.recordFailure();
        }
        assertTrue(b.blocks(), "failed to trip the breaker");
    }
}
