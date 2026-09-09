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
        /**
         * Backend produced no response head this long <em>after the request was
         * fully sent</em> -> 504. Armed at the end of the upload, not at its
         * start, so a slow client is never killed by a limit that exists to catch
         * a silent backend (OPQ-009).
         */
        long requestTimeoutMs,
        /**
         * No byte moved in either direction this long while our own backpressure
         * is holding the client silent -> 504. 0 disables it.
         *
         * <p>This is the other half of {@code requestTimeoutMs}: between them
         * every phase of a request has exactly one guard, and a mid-upload stall
         * can no longer hang forever in the gap that suppressing the idle timer
         * would otherwise leave (SUR-002, OPQ-009).
         */
        long stallTimeoutMs,
        /** TCP connect to a backend must complete in this long -> 502. */
        long connectTimeoutMs,
        /**
         * Concurrently in-flight requests before shedding with 503; 0 disables
         * admission control. Unlike the caps above this one is not a property of
         * a single request, it is a property of this process's capacity, so the
         * defensible value is measured rather than reasoned — see MEA-006.
         */
        int maxInFlight,
        /** Pending write bytes at which a channel becomes writable again. */
        int writeBufferLowBytes,
        /**
         * Pending write bytes at which a channel stops being writable, which is
         * what trips the {@code autoRead} valve (DEC-005). Low enough and the
         * valve thrashes; high enough and this is just a buffer with extra steps.
         */
        int writeBufferHighBytes) {

    public static ServerConfig defaults() {
        return new ServerConfig(
                8080, 9090, 4096, 50_000,
                16 * 1024, 8 * 1024, 100L * 1024 * 1024,
                60_000, 30_000, 5_000, 1_000, 0,
                32 * 1024, 64 * 1024);
    }
}
