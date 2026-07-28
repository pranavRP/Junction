package io.junction.it;

import io.junction.chaos.ChaosBackend;
import io.junction.config.BackendConfig;
import io.junction.config.JunctionConfig;
import io.junction.config.PoolConfig;
import io.junction.config.RouteConfig;
import io.junction.config.ServerConfig;
import io.junction.net.JunctionServer;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Starts a real ChaosBackend and a real JunctionServer on ephemeral ports.
 *
 * <p>Real sockets on purpose (R-20): the behaviours Phase 1 claims — streaming,
 * framing, keep-alive, timeouts — only exist at the socket layer. An
 * EmbeddedChannel test would assert the shape of the code rather than the
 * behaviour of the proxy.
 */
final class ProxyHarness implements AutoCloseable {

    private final ChaosBackend backend;
    private final JunctionServer server;

    private ProxyHarness(ChaosBackend backend, JunctionServer server) {
        this.backend = backend;
        this.server = server;
    }

    static ProxyHarness start() throws Exception {
        return start(UnaryOperator.identity());
    }

    /** @param tune adjusts the default server config, e.g. to shrink a limit */
    static ProxyHarness start(UnaryOperator<ServerConfig> tune) throws Exception {
        return start(tune, List.of(new RouteConfig("*", "/", "api")));
    }

    static ProxyHarness startWithRoutes(List<RouteConfig> routes) throws Exception {
        return start(UnaryOperator.identity(), routes);
    }

    static ProxyHarness start(UnaryOperator<ServerConfig> tune, List<RouteConfig> routes)
            throws Exception {
        ChaosBackend backend = new ChaosBackend(0, "b1");
        backend.start();

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
                List.of(PoolConfig.of("api",
                        List.of(new BackendConfig("b1", "127.0.0.1", backend.boundPort(), 100)))),
                routes);

        JunctionServer server = new JunctionServer(config);
        server.start();
        return new ProxyHarness(backend, server);
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

    int backendPort() {
        return backend.boundPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    @Override
    public void close() {
        server.stop();
        backend.stop();
    }
}
