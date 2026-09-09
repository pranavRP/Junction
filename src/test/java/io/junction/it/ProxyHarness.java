package io.junction.it;

import io.junction.chaos.ChaosBackend;
import io.junction.config.RetryConfig;
import io.junction.config.BreakerConfig;
import io.junction.config.BackendConfig;
import io.junction.config.HealthConfig;
import io.junction.config.JunctionConfig;
import io.junction.config.PoolConfig;
import io.junction.config.RouteConfig;
import io.junction.config.ServerConfig;
import io.junction.config.Strategy;
import io.junction.config.UpstreamPoolConfig;
import io.junction.net.JunctionServer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Starts real ChaosBackends and a real JunctionServer on ephemeral ports.
 *
 * <p>Real sockets on purpose (R-20): streaming, framing, keep-alive, pooling and
 * timeouts only exist at the socket layer. An EmbeddedChannel test would assert
 * the shape of the code rather than the behaviour of the proxy.
 */
final class ProxyHarness implements AutoCloseable {

    private final List<ChaosBackend> backends;
    private final JunctionServer server;

    private ProxyHarness(List<ChaosBackend> backends, JunctionServer server) {
        this.backends = backends;
        this.server = server;
    }

    static ProxyHarness start() throws Exception {
        return start(UnaryOperator.identity());
    }

    static ProxyHarness start(UnaryOperator<ServerConfig> tune) throws Exception {
        return start(tune, List.of(new RouteConfig("*", "/", "api")), 1, Strategy.P2C);
    }

    static ProxyHarness startWithRoutes(List<RouteConfig> routes) throws Exception {
        return start(UnaryOperator.identity(), routes, 1, Strategy.P2C);
    }

    /** Multi-backend harness for balancing and ejection tests. */
    static ProxyHarness startWithBackends(int backendCount, Strategy strategy) throws Exception {
        return start(UnaryOperator.identity(),
                List.of(new RouteConfig("*", "/", "api")), backendCount, strategy);
    }

    /** Fast probes so ejection tests do not wait on a production interval. */
    static final HealthConfig FAST_PROBES = new HealthConfig("/healthz", 200, 100, 2, 2);

    /**
     * Probes that will not fire during a short test. Resilience tests need the
     * data path to keep choosing a dead backend, because what they are measuring
     * is what the data path does about it — an active probe quietly ejecting the
     * backend first would make the test pass without testing anything.
     */
    static final HealthConfig DORMANT_PROBES = new HealthConfig("/healthz", 60_000, 1_000, 2, 100);

    /** Phase 3 harness: panic, breaker and retry are per-pool, so tests set them per-pool. */
    static ProxyHarness startWithPolicy(int backendCount,
                                        HealthConfig health,
                                        int panicPercent,
                                        BreakerConfig breaker,
                                        RetryConfig retry) throws Exception {
        return start(UnaryOperator.identity(), List.of(new RouteConfig("*", "/", "api")),
                backendCount, Strategy.P2C, health, panicPercent, breaker, retry);
    }

    static ProxyHarness start(UnaryOperator<ServerConfig> tune,
                              List<RouteConfig> routes,
                              int backendCount,
                              Strategy strategy) throws Exception {
        // Phase 3 policy off by default, so the Phase 1 and 2 tests keep asserting
        // exactly what they asserted before: a dead pool is a 503, not a panic pick.
        return start(tune, routes, backendCount, strategy, FAST_PROBES,
                0, BreakerConfig.disabled(), RetryConfig.disabled());
    }

    static ProxyHarness start(UnaryOperator<ServerConfig> tune,
                              List<RouteConfig> routes,
                              int backendCount,
                              Strategy strategy,
                              HealthConfig health,
                              int panicPercent,
                              BreakerConfig breaker,
                              RetryConfig retry) throws Exception {
        List<ChaosBackend> started = new ArrayList<>();
        List<BackendConfig> configs = new ArrayList<>();
        for (int i = 0; i < backendCount; i++) {
            ChaosBackend b = new ChaosBackend(0, "b" + i);
            b.start();
            started.add(b);
            configs.add(new BackendConfig("b" + i, "127.0.0.1", b.boundPort(), 100));
        }

        ServerConfig base = new ServerConfig(
                0,                       // ephemeral: boundPort() reports the real one
                0,
                1024,
                10_000,
                8 * 1024,                // max header bytes -> 431
                4 * 1024,                // max uri length   -> 414
                4L * 1024 * 1024 * 1024, // 4 GiB so the 1 GB gate streams
                60_000,                  // idle    -> 408
                30_000,                  // request -> 504
                1_000);                  // connect -> 502

        JunctionConfig config = new JunctionConfig(
                tune.apply(base),
                List.of(new PoolConfig("api", strategy, "", panicPercent, 0,
                        health, breaker, retry, UpstreamPoolConfig.defaults(), configs)),
                routes);

        JunctionServer server = new JunctionServer(config);
        server.start();
        return new ProxyHarness(started, server);
    }

    // Records have no wither syntax, so these keep the tests readable at the
    // call site instead of restating all ten components each time.

    static ServerConfig withRequestTimeout(ServerConfig s, long ms) {
        return new ServerConfig(s.port(), s.adminPort(), s.backlog(), s.maxConnections(),
                s.maxHeaderBytes(), s.maxUriLength(), s.maxBodyBytes(),
                s.idleTimeoutMs(), ms, s.connectTimeoutMs());
    }

    static ServerConfig withIdleTimeout(ServerConfig s, long ms) {
        return new ServerConfig(s.port(), s.adminPort(), s.backlog(), s.maxConnections(),
                s.maxHeaderBytes(), s.maxUriLength(), s.maxBodyBytes(),
                ms, s.requestTimeoutMs(), s.connectTimeoutMs());
    }

    static ServerConfig withMaxBodyBytes(ServerConfig s, long bytes) {
        return new ServerConfig(s.port(), s.adminPort(), s.backlog(), s.maxConnections(),
                s.maxHeaderBytes(), s.maxUriLength(), bytes,
                s.idleTimeoutMs(), s.requestTimeoutMs(), s.connectTimeoutMs());
    }

    static ServerConfig withMaxHeaderBytes(ServerConfig s, int bytes) {
        return new ServerConfig(s.port(), s.adminPort(), s.backlog(), s.maxConnections(),
                bytes, s.maxUriLength(), s.maxBodyBytes(),
                s.idleTimeoutMs(), s.requestTimeoutMs(), s.connectTimeoutMs());
    }

    static ServerConfig withMaxUriLength(ServerConfig s, int len) {
        return new ServerConfig(s.port(), s.adminPort(), s.backlog(), s.maxConnections(),
                s.maxHeaderBytes(), len, s.maxBodyBytes(),
                s.idleTimeoutMs(), s.requestTimeoutMs(), s.connectTimeoutMs());
    }

    int port() {
        return server.boundPort();
    }

    JunctionServer server() {
        return server;
    }

    ChaosBackend backend(int index) {
        return backends.get(index);
    }

    List<ChaosBackend> backends() {
        return backends;
    }

    int backendPort() {
        return backends.get(0).boundPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    @Override
    public void close() {
        server.stop();
        backends.forEach(ChaosBackend::stop);
    }
}
