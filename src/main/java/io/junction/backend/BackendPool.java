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
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * A named pool: its backends, their authoritative health, and the balancer that
 * chooses between them.
 *
 * <p>Immutable membership. A config reload builds a new pool rather than mutating
 * this one, which is what lets an in-flight request keep using the old object
 * graph safely (R-8). Health state is carried across a reload by backend id, so
 * reloading does not reset everything to unknown and trigger a probe storm.
 *
 * <p><b>Panic mode (FR-3.6)</b> lives here rather than in the strategies. Below
 * {@code panic_percent} available backends the pool flips a shared flag that
 * {@link BackendRuntime#selectable()} reads, so every strategy enters panic at
 * once and keeps its own selection behaviour while in it — consistent hashing
 * still hashes, round robin still rotates. A separate "panic balancer" would have
 * meant a second implementation of each strategy that only runs during outages,
 * which is the code least likely to be right when it finally executes.
 */
public final class BackendPool {

    private final PoolConfig config;
    private final List<BackendRuntime> backends;
    private final Balancer balancer;
    private final RetryBudget retryBudget;
    /** The same instance every {@link BackendRuntime} in this pool reads. */
    private final AtomicBoolean panic;

    private BackendPool(PoolConfig config, List<BackendRuntime> backends,
                        AtomicBoolean panic, RetryBudget retryBudget) {
        this.config = config;
        this.backends = List.copyOf(backends);
        this.panic = panic;
        this.retryBudget = retryBudget;
        this.balancer = Balancers.create(config, this.backends);
    }

    public static BackendPool create(PoolConfig config, Clock clock) {
        AtomicBoolean panic = new AtomicBoolean();
        List<BackendRuntime> runtimes = new ArrayList<>(config.backends().size());
        for (BackendConfig b : config.backends()) {
            runtimes.add(new BackendRuntime(
                    b,
                    new HealthTracker(config.health(), config.slowStartMs(), clock),
                    config.breaker(),
                    panic,
                    clock));
        }
        return new BackendPool(config, runtimes, panic, new RetryBudget(config.retry(), clock));
    }

    /**
     * Routes one request. Re-evaluates panic first, because panic changes the
     * answer to every subsequent {@code selectable()} call the balancer makes.
     *
     * <p>ponytail: the panic check is an O(backends) scan per pick. That is the
     * same order the strategies already scan in, and pools here are tens of
     * backends, not thousands. If a pool ever gets large enough for it to matter,
     * recompute on health transitions instead — but measure first.
     */
    public PickResult pick(String hashKey) {
        refreshPanic();
        return balancer.pick(hashKey);
    }

    private void refreshPanic() {
        if (config.panicPercent() <= 0 || backends.isEmpty()) {
            return;
        }
        int available = 0;
        for (int i = 0; i < backends.size(); i++) {
            if (backends.get(i).available()) {
                available++;
            }
        }
        boolean shouldPanic = available * 100 < backends.size() * config.panicPercent();
        // Write only on a transition. This flag is read by every event loop, so
        // writing it per request would bounce its cache line across every core
        // for a value that changes a handful of times a day.
        if (shouldPanic != panic.get()) {
            panic.set(shouldPanic);
        }
    }

    public boolean inPanic() {
        return panic.get();
    }

    public RetryBudget retryBudget() {
        return retryBudget;
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

    /** Total upstream attempts across the pool, retries included (MEA-005). */
    public long attempts() {
        long total = 0;
        for (BackendRuntime b : backends) {
            total += b.attempts();
        }
        return total;
    }
}
