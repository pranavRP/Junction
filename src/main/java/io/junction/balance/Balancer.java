package io.junction.balance;

/**
 * Chooses one backend from a pool.
 *
 * <p>A balancer is constructed for a fixed backend list and lives as long as that
 * config generation. Reload builds a new one rather than mutating this one, so
 * {@link #pick} never races a membership change.
 *
 * <p><b>No dependency on HTTP types on purpose.</b> The hash key arrives as an
 * already-extracted string, which keeps this package free of the codec layer
 * (R-11 forbids depending upward) and makes every strategy testable without a
 * socket or a request object.
 *
 * <p>Implementations must be safe to call concurrently from many event loops and
 * must not block (R-3).
 */
public interface Balancer {

    /**
     * @param hashKey value of the configured hash key for this request, or an
     *                empty string; only consistent hashing consults it
     */
    PickResult pick(String hashKey);

    /** Stable token naming the strategy, for logs and metrics. */
    String name();
}
