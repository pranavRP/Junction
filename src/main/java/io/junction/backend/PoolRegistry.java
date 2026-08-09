package io.junction.backend;

import io.junction.config.JunctionConfig;
import io.junction.config.PoolConfig;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** All pools for one config generation, resolvable by name. */
public final class PoolRegistry {

    private final Map<String, BackendPool> pools;

    private PoolRegistry(Map<String, BackendPool> pools) {
        this.pools = Map.copyOf(pools);
    }

    public static PoolRegistry create(JunctionConfig config, Clock clock) {
        Map<String, BackendPool> built = new LinkedHashMap<>();
        for (PoolConfig p : config.pools()) {
            built.put(p.name(), BackendPool.create(p, clock));
        }
        return new PoolRegistry(built);
    }

    public Optional<BackendPool> byName(String name) {
        return Optional.ofNullable(pools.get(name));
    }

    public Iterable<BackendPool> pools() {
        return pools.values();
    }

    public int size() {
        return pools.size();
    }
}
