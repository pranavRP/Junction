package io.junction.config;

import java.util.List;
import java.util.Optional;

/**
 * Root of the validated, immutable config graph.
 *
 * <p>Immutability is load-bearing: hot reload (Phase 6) builds an entirely new
 * graph, validates it, and swaps one volatile reference. Nothing here is ever
 * mutated in place, so an in-flight request can keep reading the old graph
 * safely while a new one is published (R-8).
 */
public record JunctionConfig(
        ServerConfig server,
        List<PoolConfig> pools,
        List<RouteConfig> routes) {

    public JunctionConfig {
        pools = List.copyOf(pools);
        routes = List.copyOf(routes);
    }

    public Optional<PoolConfig> pool(String name) {
        return pools.stream().filter(p -> p.name().equals(name)).findFirst();
    }
}
