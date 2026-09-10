package io.junction.it;

import io.junction.config.BreakerConfig;
import io.junction.config.RetryConfig;
import io.junction.config.RouteConfig;
import io.junction.config.ServerConfig;
import io.junction.config.Strategy;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The one topology every Phase 4 measurement runs against, so the knee found by
 * the sweep is the knee the gate is later held to. Measuring capacity in one
 * configuration and asserting it in another is how a gate ends up green against a
 * number that was never about it.
 *
 * <p><b>Probes and the breaker are off.</b> The harness's test probes fire every
 * 200ms with a 100ms timeout, and a backend saturated by this very load answers
 * them late — active health checking would eject the pool mid-run and the 503s
 * that followed would have nothing to do with admission control. That is a
 * fixture setting rather than the shipped one (2s/500ms), but it does make health
 * checking and throughput measurement mutually exclusive inside one process.
 */
final class Benches {

    private Benches() {}

    /** Three backends, matching the shipped compose topology. */
    static ProxyHarness steady(UnaryOperator<ServerConfig> tune) throws Exception {
        return ProxyHarness.start(tune, List.of(new RouteConfig("*", "/", "api")), 3,
                Strategy.P2C, ProxyHarness.DORMANT_PROBES, 0,
                BreakerConfig.disabled(), RetryConfig.disabled());
    }
}
