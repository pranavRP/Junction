package io.junction.config;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static final String VALID = """
            server:
              port: 8080
              max_body_bytes: 1048576
            pools:
              - name: api
                backends:
                  - { id: b1, host: backend-1, port: 8000, weight: 100 }
            routes:
              - { host: "*", prefix: "/", pool: api }
            """;

    private static ConfigResult parse(String yaml) {
        return ConfigLoader.parse(new StringReader(yaml));
    }

    private static List<String> errors(String yaml) {
        ConfigResult r = parse(yaml);
        assertInstanceOf(ConfigResult.Invalid.class, r, "expected config to be rejected");
        return ((ConfigResult.Invalid) r).errors();
    }

    private static void assertHasError(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)),
                "expected an error containing '" + fragment + "', got " + errors);
    }

    @Test
    void parsesValidConfig() {
        ConfigResult r = parse(VALID);
        assertInstanceOf(ConfigResult.Valid.class, r);

        JunctionConfig c = ((ConfigResult.Valid) r).config();
        assertEquals(8080, c.server().port());
        assertEquals(1_048_576L, c.server().maxBodyBytes());
        assertEquals(1, c.pools().size());
        assertEquals("b1", c.pools().get(0).backends().get(0).id());
        assertEquals("api", c.routes().get(0).pool());
    }

    @Test
    void appliesDefaultsWhenServerBlockAbsent() {
        ConfigResult r = parse("""
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """);
        assertInstanceOf(ConfigResult.Valid.class, r);
        JunctionConfig c = ((ConfigResult.Valid) r).config();
        assertEquals(ServerConfig.defaults().port(), c.server().port());
        assertEquals(100, c.pools().get(0).backends().get(0).weight(), "absent weight defaults");
    }

    /**
     * The point of accumulating: an operator fixing config one error per restart
     * is the experience this avoids.
     */
    @Test
    void reportsEveryErrorInOnePass() {
        List<String> errors = errors("""
                server:
                  port: 99999
                  backlog: -1
                pools:
                  - name: api
                    backends:
                      - { id: b1, host: h, port: 0 }
                routes:
                  - { host: "*", prefix: "relative", pool: nonexistent }
                """);

        assertHasError(errors, "server.port must be 1..65535");
        assertHasError(errors, "server.backlog must be > 0");
        assertHasError(errors, "backends[0].port must be 1..65535");
        assertHasError(errors, "prefix must start with '/'");
        assertHasError(errors, "unknown pool 'nonexistent'");
        assertTrue(errors.size() >= 5, "expected all five errors, got " + errors);
    }

    @Test
    void rejectsUnknownKeySoTyposDoNotSilentlyDefault() {
        assertHasError(errors("""
                server:
                  prt: 8080
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "unknown key 'prt'");
    }

    @Test
    void rejectsDuplicateBackendAndPoolIds() {
        assertHasError(errors("""
                pools:
                  - name: api
                    backends:
                      - { id: b1, host: h, port: 8000 }
                      - { id: b1, host: h, port: 8001 }
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "duplicates an earlier backend");

        assertHasError(errors("""
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                  - name: api
                    backends: [{ id: b2, host: h, port: 8001 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "duplicates an earlier pool");
    }

    /**
     * An invalid port falls back to the default internally so parsing can
     * continue. That fallback must not then be compared against admin_port —
     * doing so invents a conflict about a value the operator never wrote, which
     * is exactly the confusing error this loader exists to avoid.
     */
    @Test
    void invalidPortDoesNotInventAnAdminPortConflict() {
        List<String> errors = errors("""
                server:
                  port: 99999
                  admin_port: 8080
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """);

        assertHasError(errors, "server.port must be 1..65535");
        assertTrue(errors.stream().noneMatch(e -> e.contains("admin_port must differ")),
                "must not report a conflict against a defaulted port, got " + errors);
    }

    /** 8080.7 is not a port. Truncating it to 8080 silently would be worse. */
    @Test
    void rejectsNonIntegralNumbers() {
        assertHasError(errors("""
                server:
                  port: 8080.7
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "server.port must be a whole number");
    }

    /** Beyond long range SnakeYAML yields BigInteger, whose longValue() wraps. */
    @Test
    void rejectsNumbersTooLargeForLong() {
        assertHasError(errors("""
                server:
                  max_connections: 99999999999999999999
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "server.max_connections is out of range");
    }

    @Test
    void rejectsAdminPortEqualToDataPort() {
        assertHasError(errors("""
                server:
                  port: 8080
                  admin_port: 8080
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "admin_port must differ");
    }

    @Test
    void rejectsMissingRequiredSections() {
        assertHasError(errors("server: { port: 8080 }"), "pools is required");
        assertHasError(errors("""
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                """), "routes is required");
    }

    @Test
    void rejectsEmptyAndMalformedDocuments() {
        assertHasError(errors(""), "empty");
        assertHasError(errors("- just\n- a\n- list\n"), "top level must be a mapping");
        assertHasError(errors("server: {port: 8080\n"), "YAML syntax error");
    }

    @Test
    void parsesStrategyHealthAndPoolBlocks() {
        ConfigResult r = parse("""
                pools:
                  - name: api
                    strategy: least_connections
                    health:
                      path: /ping
                      interval_ms: 1000
                      timeout_ms: 250
                      healthy_threshold: 4
                      unhealthy_threshold: 5
                    pool:
                      max_idle_per_backend: 8
                      max_connect_ms: 300
                      idle_ttl_ms: 15000
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """);
        assertInstanceOf(ConfigResult.Valid.class, r);

        PoolConfig p = ((ConfigResult.Valid) r).config().pools().get(0);
        assertEquals(Strategy.LEAST_CONNECTIONS, p.strategy());
        assertEquals("/ping", p.health().path());
        assertEquals(250L, p.health().timeoutMs());
        assertEquals(5, p.health().unhealthyThreshold());
        assertEquals(8, p.pool().maxIdlePerBackend());
        assertEquals(15_000L, p.pool().idleTtlMs());
    }

    @Test
    void defaultsToP2cWhenStrategyAbsent() {
        ConfigResult r = parse(VALID);
        assertInstanceOf(ConfigResult.Valid.class, r);
        PoolConfig p = ((ConfigResult.Valid) r).config().pools().get(0);
        assertEquals(Strategy.P2C, p.strategy());
        assertEquals(HealthConfig.defaults().path(), p.health().path());
    }

    @Test
    void rejectsUnknownStrategy() {
        assertHasError(errors("""
                pools:
                  - name: api
                    strategy: magic
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "not a known strategy: 'magic'");
    }

    @Test
    void consistentHashRequiresAWellFormedHashKey() {
        assertHasError(errors("""
                pools:
                  - name: api
                    strategy: consistent_hash
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "hash_key is required");

        assertHasError(errors("""
                pools:
                  - name: api
                    strategy: consistent_hash
                    hash_key: X-Session-Id
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "must start with 'header:' or 'cookie:'");
    }

    /** A key that silently does nothing is the same trap as a typo'd key. */
    @Test
    void rejectsHashKeyOnAStrategyThatIgnoresIt() {
        assertHasError(errors("""
                pools:
                  - name: api
                    strategy: round_robin
                    hash_key: header:X-Session-Id
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "hash_key only applies to strategy 'consistent_hash'");
    }

    /** Probes that outlive their interval stack up on an already-sick backend. */
    @Test
    void rejectsHealthTimeoutNotLessThanInterval() {
        assertHasError(errors("""
                pools:
                  - name: api
                    health: { interval_ms: 500, timeout_ms: 500 }
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "must be less than interval_ms");
    }

    @Test
    void rejectsUnknownKeysInsideHealthAndPoolBlocks() {
        assertHasError(errors("""
                pools:
                  - name: api
                    health: { intrval_ms: 1000 }
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "unknown key 'intrval_ms'");

        assertHasError(errors("""
                pools:
                  - name: api
                    pool: { max_idle: 4 }
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "unknown key 'max_idle'");
    }

    @Test
    void weightZeroIsAllowedBecauseItMeansDrain() {
        ConfigResult r = parse("""
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000, weight: 0 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """);
        assertInstanceOf(ConfigResult.Valid.class, r);
        assertEquals(0, ((ConfigResult.Valid) r).config().pools().get(0).backends().get(0).weight());
    }
}
