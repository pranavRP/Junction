package io.junction.backend;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the test moves by hand (R-24).
 *
 * <p>Exists so timing-dependent transitions can be asserted instantly and
 * deterministically. Testing a threshold by sleeping would be both slow and
 * flaky, which is why R-23 bans it outright.
 */
final class MutableClock extends Clock {

    private Instant now;

    MutableClock(long epochMillis) {
        this.now = Instant.ofEpochMilli(epochMillis);
    }

    void advanceMillis(long millis) {
        now = now.plusMillis(millis);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
