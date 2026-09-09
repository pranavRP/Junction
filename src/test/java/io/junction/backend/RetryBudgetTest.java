package io.junction.backend;

import io.junction.config.RetryConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBudgetTest {

    private static final long T0 = 1_000_000L;

    /** 10% of volume, no floor — so the ratio is the only thing under test. */
    private static final RetryConfig RATIO_ONLY = new RetryConfig(2, 10, 0);

    @Test
    void allowsRetriesUpToThePercentageOfVolume() {
        MutableClock clock = new MutableClock(T0);
        RetryBudget budget = new RetryBudget(RATIO_ONLY, clock);

        for (int i = 0; i < 100; i++) {
            budget.recordRequest();
        }

        for (int i = 0; i < 10; i++) {
            assertTrue(budget.tryRetry(), "retry " + (i + 1) + " is inside a 10% budget on 100 requests");
        }
        assertFalse(budget.tryRetry(), "the 11th retry is over budget");
    }

    /**
     * This is the Phase 3 gate in one assertion: with every backend down, every
     * request fails, and without the budget every request would retry. Upstream
     * volume must stay at 1.1x, not 2x.
     */
    @Test
    void aTotalOutageCannotAmplifyUpstreamVolume() {
        MutableClock clock = new MutableClock(T0);
        RetryBudget budget = new RetryBudget(RATIO_ONLY, clock);

        int requests = 1_000;
        long attempts = 0;
        for (int i = 0; i < requests; i++) {
            budget.recordRequest();
            attempts++;
            if (budget.tryRetry()) {   // every single one fails, so every one asks
                attempts++;
            }
        }

        assertTrue(attempts <= requests * 11 / 10,
                "upstream volume was " + attempts + " for " + requests + " requests");
    }

    /** Without a floor, a pool serving a trickle could never retry anything. */
    @Test
    void theFloorLetsALowTrafficPoolRetry() {
        MutableClock clock = new MutableClock(T0);
        RetryBudget budget = new RetryBudget(new RetryConfig(2, 10, 3), clock);

        budget.recordRequest();

        assertEquals(30, budget.allowance(T0 / 1_000), "3/s across a 10s window");
        assertTrue(budget.tryRetry(), "10% of one request is zero, so the floor has to carry it");
    }

    @Test
    void spentRetriesAgeOutOfTheWindow() {
        MutableClock clock = new MutableClock(T0);
        RetryBudget budget = new RetryBudget(RATIO_ONLY, clock);

        for (int i = 0; i < 100; i++) {
            budget.recordRequest();
        }
        for (int i = 0; i < 10; i++) {
            assertTrue(budget.tryRetry());
        }
        assertFalse(budget.tryRetry(), "budget is spent");

        // Past the whole window: the old requests and the old retries both expire.
        clock.advanceMillis(11_000);
        assertEquals(0, budget.requestsInWindow(), "stale volume must not keep funding retries");
        assertEquals(0, budget.retriesInWindow(), "stale spend must not keep blocking them");
    }

    @Test
    void aDisabledPolicyNeverRetries() {
        RetryBudget budget = new RetryBudget(RetryConfig.disabled(), new MutableClock(T0));

        budget.recordRequest();

        assertFalse(budget.tryRetry());
    }
}
