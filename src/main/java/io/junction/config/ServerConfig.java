package io.junction.config;

/**
 * Listener and per-connection limits.
 *
 * <p>Every limit here exists because the corresponding resource is otherwise
 * unbounded (R-5). The status code each one produces is fixed by FR-1.6 and
 * asserted in the integration tests.
 */
public record ServerConfig(
        int port,
        int adminPort,
        int backlog,
        int maxConnections,
        /** Header block cap. Exceeded -> 431. */
        int maxHeaderBytes,
        /** Request line cap. Exceeded -> 414. */
        int maxUriLength,
        /** Request body cap. Exceeded -> 413. */
        long maxBodyBytes,
        /** No bytes from an idle client for this long -> 408 and close. */
        long idleTimeoutMs,
        /** Backend produced no response head in this long -> 504. */
        long requestTimeoutMs,
        /** TCP connect to a backend must complete in this long -> 502. */
        long connectTimeoutMs,
        /**
         * Concurrently in-flight requests before shedding with 503; 0 disables
         * admission control. Unlike the caps above this one is not a property of
         * a single request, it is a property of this process's capacity, so the
         * defensible value is measured rather than reasoned — see MEA-006.
         */
        int maxInFlight) {

    public static ServerConfig defaults() {
        return new ServerConfig(
                8080, 9090, 4096, 50_000,
                16 * 1024, 8 * 1024, 100L * 1024 * 1024,
                60_000, 30_000, 1_000, 0);
    }
}
