package io.junction.backend;

import io.junction.chaos.ChaosBackend;
import io.junction.config.BackendConfig;
import io.junction.config.HealthConfig;
import io.junction.config.PoolConfig;
import io.junction.config.Strategy;
import io.junction.config.UpstreamPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Probes are driven synchronously via {@code probeOnce} rather than by starting
 * the scheduler and waiting. That keeps these deterministic and instant — R-23
 * bans sleep-based tests because they are slow and flaky, and a health checker
 * asserted by sleeping would be both.
 */
class HealthCheckerTest {

    private ChaosBackend backend;
    private BackendPool pool;
    private HealthChecker checker;

    private static PoolConfig poolConfig(int port, int healthyThreshold, int unhealthyThreshold) {
        return new PoolConfig("api", Strategy.P2C, "",
                new HealthConfig("/healthz", 2_000, 500, healthyThreshold, unhealthyThreshold),
                UpstreamPoolConfig.defaults(),
                List.of(new BackendConfig("b1", "127.0.0.1", port, 100)));
    }

    @BeforeEach
    void setUp() throws Exception {
        backend = new ChaosBackend(0, "b1");
        backend.start();
        pool = BackendPool.create(poolConfig(backend.boundPort(), 2, 3), Clock.systemUTC());
        checker = new HealthChecker(PoolRegistry.create(
                new io.junction.config.JunctionConfig(
                        io.junction.config.ServerConfig.defaults(),
                        List.of(pool.config()),
                        List.of(new io.junction.config.RouteConfig("*", "/", "api"))),
                Clock.systemUTC()));
    }

    @AfterEach
    void tearDown() {
        if (checker != null) {
            checker.close();
        }
        if (backend != null) {
            backend.stop();
        }
    }

    private BackendRuntime only() {
        return pool.backends().get(0);
    }

    @Test
    void healthyBackendStaysHealthy() {
        BackendRuntime b = only();
        for (int i = 0; i < 5; i++) {
            checker.probeOnce(pool, b);
        }
        assertTrue(b.selectable());
        assertInstanceOf(HealthState.Healthy.class, b.health());
    }

    @Test
    void failingBackendIsEjectedAfterTheUnhealthyThreshold() {
        BackendRuntime b = only();
        backend.setHealthy(false);

        assertTrue(checker.probeOnce(pool, b).acceptsTraffic(), "1 of 3");
        assertTrue(checker.probeOnce(pool, b).acceptsTraffic(), "2 of 3");
        assertFalse(checker.probeOnce(pool, b).acceptsTraffic(), "3 of 3 ejects");

        assertInstanceOf(HealthState.Unhealthy.class, b.health());
        assertEquals("status_503", ((HealthState.Unhealthy) b.health()).reason(),
                "the reason must name the actual failure, not a generic label");
    }

    @Test
    void recoveredBackendIsReadmittedAfterTheHealthyThreshold() {
        BackendRuntime b = only();
        backend.setHealthy(false);
        for (int i = 0; i < 3; i++) {
            checker.probeOnce(pool, b);
        }
        assertFalse(b.selectable());

        backend.setHealthy(true);
        assertFalse(checker.probeOnce(pool, b).acceptsTraffic(), "1 of 2");
        assertTrue(checker.probeOnce(pool, b).acceptsTraffic(), "2 of 2 re-admits");
    }

    /** An unreachable backend must be ejected, and say so distinctly from a 503. */
    @Test
    void unreachableBackendIsEjectedWithAConnectFailure() {
        BackendPool dead = BackendPool.create(
                poolConfig(1, 2, 1), Clock.systemUTC());   // port 1: nothing listens
        BackendRuntime b = dead.backends().get(0);

        HealthState after = checker.probeOnce(dead, b);

        assertFalse(after.acceptsTraffic());
        String reason = ((HealthState.Unhealthy) after).reason();
        assertTrue(reason.equals("connect_failure") || reason.equals("io_error"),
                "expected a connection-level reason, got " + reason);
    }

    @Test
    void probeUsesTheConfiguredPath() throws Exception {
        // /_chaos/unhealthy is a control endpoint, so pointing the probe at it
        // proves the configured path is really what gets requested.
        BackendPool custom = BackendPool.create(
                new PoolConfig("api", Strategy.P2C, "",
                        new HealthConfig("/_chaos/unhealthy", 2_000, 500, 1, 1),
                        UpstreamPoolConfig.defaults(),
                        List.of(new BackendConfig("b1", "127.0.0.1", backend.boundPort(), 100))),
                Clock.systemUTC());

        assertTrue(backend.isHealthy());
        checker.probeOnce(custom, custom.backends().get(0));
        assertFalse(backend.isHealthy(), "the probe did not hit the configured path");
    }

    @Test
    void drainedBackendIsNotRevivedByAPassingProbe() {
        BackendRuntime b = only();
        b.healthTracker().apply(new HealthEvent.DrainRequested());

        checker.probeOnce(pool, b);
        checker.probeOnce(pool, b);

        assertInstanceOf(HealthState.Draining.class, b.health(),
                "a deliberate drain must survive good probe results");
    }
}
