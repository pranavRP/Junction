package io.junction.config;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // ------------------------------------------------- Phase 3 resilience keys

    private static final String RESILIENCE = """
            pools:
              - name: api
                panic_percent: 40
                slow_start_ms: 5000
                breaker:
                  consecutive_failures: 7
                  base_cooldown_ms: 500
                  max_cooldown_ms: 20000
                  half_open_probes: 2
                retry:
                  max_attempts: 3
                  budget_percent: 25
                  min_per_second: 5
                backends: [{ id: b1, host: h, port: 8000 }]
            routes:
              - { host: "*", prefix: "/", pool: api }
            """;

    @Test
    void parsesTheResilienceBlocks() {
        ConfigResult r = parse(RESILIENCE);
        assertInstanceOf(ConfigResult.Valid.class, r);

        PoolConfig pool = ((ConfigResult.Valid) r).config().pools().get(0);
        assertEquals(40, pool.panicPercent());
        assertEquals(5_000, pool.slowStartMs());
        assertEquals(7, pool.breaker().consecutiveFailures());
        assertEquals(500, pool.breaker().baseCooldownMs());
        assertEquals(2, pool.breaker().halfOpenProbes());
        assertEquals(3, pool.retry().maxAttempts());
        assertEquals(25, pool.retry().budgetPercent());
        assertEquals(5, pool.retry().minPerSecond());
    }

    /** Absent blocks must still produce working policy, not a disabled one. */
    @Test
    void resilienceDefaultsApplyWhenTheBlocksAreAbsent() {
        PoolConfig pool = ((ConfigResult.Valid) parse(VALID)).config().pools().get(0);

        assertEquals(50, pool.panicPercent(), "panic on by default, at Envoy's threshold");
        assertEquals(0, pool.slowStartMs(), "slow start off by default: it is opt-in behaviour");
        assertTrue(pool.breaker().enabled());
        assertTrue(pool.retry().enabled());
    }

    /** 0 means off for these two, so the loader must not reject it as non-positive. */
    @Test
    void zeroDisablesPanicAndTheBreakerRatherThanFailing() {
        ConfigResult r = parse("""
                pools:
                  - name: api
                    panic_percent: 0
                    breaker: { consecutive_failures: 0 }
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """);
        assertInstanceOf(ConfigResult.Valid.class, r);

        PoolConfig pool = ((ConfigResult.Valid) r).config().pools().get(0);
        assertEquals(0, pool.panicPercent());
        assertTrue(!pool.breaker().enabled());
    }

    @Test
    void rejectsAWriteBufferCeilingBelowItsFloor() {
        assertHasError(errors("""
                server:
                  write_buffer_low_bytes: 65536
                  write_buffer_high_bytes: 32768
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "server.write_buffer_high_bytes (32768) must be >=");
    }

    @Test
    void stallTimeoutIsOffAtZero() {
        ConfigResult r = parse("""
                server:
                  stall_timeout_ms: 0
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """);
        assertInstanceOf(ConfigResult.Valid.class, r);
        assertEquals(0, ((ConfigResult.Valid) r).config().server().stallTimeoutMs());
    }

    @Test
    void maxInFlightIsOffAtZeroAndRejectedBelowIt() {
        ConfigResult off = parse("""
                server:
                  max_in_flight: 0
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """);
        assertInstanceOf(ConfigResult.Valid.class, off);
        assertEquals(0, ((ConfigResult.Valid) off).config().server().maxInFlight());

        assertHasError(errors("""
                server:
                  max_in_flight: -1
                pools:
                  - name: api
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "server.max_in_flight must be >= 0");
    }

    @Test
    void rejectsAPanicPercentOutsideItsRange() {
        assertHasError(errors("""
                pools:
                  - name: api
                    panic_percent: 140
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "panic_percent must be 0..100");
    }

    /**
     * A cooldown ceiling below its own floor means the doubling never happens and
     * the base the operator wrote never applies — config that looks tuned and is
     * not.
     */
    @Test
    void rejectsACooldownCeilingBelowItsFloor() {
        assertHasError(errors("""
                pools:
                  - name: api
                    breaker: { base_cooldown_ms: 5000, max_cooldown_ms: 1000 }
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "max_cooldown_ms (1000) must be >= base_cooldown_ms (5000)");
    }

    /** Retries with a zero budget are a safety net that can never catch anything. */
    @Test
    void rejectsRetriesThatCanNeverHappen() {
        assertHasError(errors("""
                pools:
                  - name: api
                    retry: { max_attempts: 3, budget_percent: 0, min_per_second: 0 }
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "no retry can ever happen");
    }

    /**
     * The config the repo actually ships must load. It is the file the quickstart
     * tells people to run and the one docker compose mounts, so a key renamed in
     * the loader and not in the YAML is a broken {@code docker compose up} — the
     * one path every reader takes first.
     */
    @Test
    void theShippedConfigIsValid() {
        Path shipped = Path.of("junction.yaml");
        assertTrue(Files.isReadable(shipped), "junction.yaml missing from the repo root");

        ConfigResult r = ConfigLoader.load(shipped);
        if (r instanceof ConfigResult.Invalid invalid) {
            org.junit.jupiter.api.Assertions.fail("junction.yaml no longer loads:\n" + invalid.message());
        }
    }

    @Test
    void rejectsUnknownKeysInsideTheNewBlocks() {
        assertHasError(errors("""
                pools:
                  - name: api
                    breaker: { consecutive_faliures: 5 }
                    backends: [{ id: b1, host: h, port: 8000 }]
                routes:
                  - { host: "*", prefix: "/", pool: api }
                """), "pools[0].breaker has unknown key 'consecutive_faliures'");
    }
}
