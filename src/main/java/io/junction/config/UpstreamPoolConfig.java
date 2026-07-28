package io.junction.config;

/**
 * Upstream connection pool settings for a pool.
 *
 * <p><b>Required inequality:</b> {@code idleTtlMs} must be shorter than the
 * backend's own keep-alive timeout. If the backend expires the connection first,
 * we hand a dead socket to a request and only discover it when the write fails.
 * We cannot read the backend's setting, so this is documented rather than
 * validated — and the pool additionally checks liveness on acquire.
 *
 * @param maxIdlePerBackend idle sockets kept per backend <em>per EventLoop</em>
 * @param maxConnectMs      TCP connect budget before the attempt is a failure
 * @param idleTtlMs         how long an unused pooled socket survives
 */
public record UpstreamPoolConfig(
        int maxIdlePerBackend,
        long maxConnectMs,
        long idleTtlMs) {

    public static UpstreamPoolConfig defaults() {
        return new UpstreamPoolConfig(64, 1_000, 30_000);
    }
}
