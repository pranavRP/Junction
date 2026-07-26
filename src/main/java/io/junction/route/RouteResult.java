package io.junction.route;

/**
 * Outcome of route resolution. Sealed rather than a nullable return (R-13): the
 * data path must handle "no route" explicitly, because it is a client-visible
 * 404 and needs a metric, not a NullPointerException.
 */
public sealed interface RouteResult {

    /** @param pool name of the pool to forward to */
    record Matched(String pool) implements RouteResult {}

    /** @param reason closed-enum-ish detail for the metrics {@code reason} label (R-33) */
    record NoMatch(String reason) implements RouteResult {}
}
