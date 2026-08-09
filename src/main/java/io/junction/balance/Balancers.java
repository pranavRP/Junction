package io.junction.balance;

import io.junction.backend.BackendRuntime;
import io.junction.config.PoolConfig;
import io.junction.config.Strategy;

import java.util.List;

/** Builds the balancer a pool's configured strategy calls for. */
public final class Balancers {

    private Balancers() {}

    public static Balancer create(PoolConfig config, List<BackendRuntime> backends) {
        return switch (config.strategy()) {
            case ROUND_ROBIN -> new RoundRobinBalancer(backends);
            case LEAST_CONNECTIONS -> new LeastConnectionsBalancer(backends);
            case P2C -> new P2CBalancer(backends);
            case CONSISTENT_HASH -> new ConsistentHashBalancer(backends);
        };
    }

    /**
     * Cheap pre-check so the strategies can scan without an unbounded fallback
     * path. Under a total pool outage this is the branch every request takes, so
     * it stays O(backends) rather than O(schedule).
     */
    static boolean anySelectable(List<BackendRuntime> backends) {
        for (int i = 0; i < backends.size(); i++) {
            if (backends.get(i).selectable()) {
                return true;
            }
        }
        return false;
    }

    static PickResult noneAvailable(List<BackendRuntime> backends) {
        return new PickResult.NoneAvailable(backends.isEmpty() ? "empty_pool" : "no_healthy_backend");
    }
}
