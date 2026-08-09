package io.junction.backend;

import io.junction.balance.Balancer;
import io.junction.balance.Balancers;
import io.junction.balance.PickResult;
import io.junction.config.BackendConfig;
import io.junction.config.PoolConfig;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A named pool: its backends, their authoritative health, and the balancer that
 * chooses between them.
 *
 * <p>Immutable membership. A config reload builds a new pool rather than mutating
 * this one, which is what lets an in-flight request keep using the old object
 * graph safely (R-8). Health state is carried across a reload by backend id, so
 * reloading does not reset everything to unknown and trigger a probe storm.
 */
public final class BackendPool {

    private final PoolConfig config;
    private final List<BackendRuntime> backends;
    private final Balancer balancer;

    private BackendPool(PoolConfig config, List<BackendRuntime> backends, Balancer balancer) {
        this.config = config;
        this.backends = List.copyOf(backends);
        this.balancer = balancer;
    }

    public static BackendPool create(PoolConfig config, Clock clock) {
        List<BackendRuntime> runtimes = new ArrayList<>(config.backends().size());
        for (BackendConfig b : config.backends()) {
            runtimes.add(new BackendRuntime(b, new HealthTracker(config.health(), clock)));
        }
        return new BackendPool(config, runtimes, Balancers.create(config, runtimes));
    }

    public PickResult pick(String hashKey) {
        return balancer.pick(hashKey);
    }

    public String name() {
        return config.name();
    }

    public PoolConfig config() {
        return config;
    }

    public String strategyName() {
        return balancer.name();
    }

    public List<BackendRuntime> backends() {
        return backends;
    }

    public Optional<BackendRuntime> byId(String id) {
        for (BackendRuntime b : backends) {
            if (b.id().equals(id)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    /** Count of backends currently eligible for traffic — for metrics and panic detection. */
    public int selectableCount() {
        int n = 0;
        for (BackendRuntime b : backends) {
            if (b.selectable()) {
                n++;
            }
        }
        return n;
    }
}
