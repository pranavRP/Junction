package io.junction.balance;

import io.junction.backend.BackendRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smooth weighted round robin.
 *
 * <p><b>Why smooth matters.</b> Naive weighted RR with weights {5,1,1} emits
 * {@code AAAAABC} — five consecutive requests to one backend. Smooth WRR emits
 * {@code ABACAAA}. The difference shows up the moment a burst is large enough to
 * trip a breaker or saturate one host's connection pool.
 *
 * <p><b>Why a precomputed schedule instead of live counters.</b> design.md §2.1
 * carries per-backend {@code current} counters mutated on every pick, which race
 * across event loops and would need either a lock (forbidden on the data path by
 * R-3) or documented imprecision. Generating the identical smooth sequence once
 * at construction turns selection into an array index behind a single atomic
 * increment: exact, allocation-free, and lock-free. The schedule depends only on
 * the weights, which are immutable for this config generation.
 *
 * <p>Weights are divided by their GCD first, so {100,100,100} costs three slots
 * rather than three hundred.
 */
final class RoundRobinBalancer implements Balancer {

    private final List<BackendRuntime> backends;
    /** Backend indices in smooth weighted order; length = sum of reduced weights. */
    private final int[] schedule;
    private final AtomicInteger cursor = new AtomicInteger();

    RoundRobinBalancer(List<BackendRuntime> backends) {
        this.backends = List.copyOf(backends);
        this.schedule = buildSchedule(this.backends);
    }

    @Override
    public PickResult pick(String hashKey) {
        if (schedule.length == 0 || !Balancers.anySelectable(backends)) {
            return Balancers.noneAvailable(backends);
        }
        int start = Math.floorMod(cursor.getAndIncrement(), schedule.length);
        // Guaranteed to terminate on a selectable backend because of the
        // pre-check above, so the scan needs no fallback branch.
        for (int i = 0; i < schedule.length; i++) {
            BackendRuntime b = backends.get(schedule[(start + i) % schedule.length]);
            if (b.selectable()) {
                return new PickResult.Chosen(b);
            }
        }
        return Balancers.noneAvailable(backends);
    }

    @Override
    public String name() {
        return "round_robin";
    }

    int[] schedule() {
        return schedule.clone();
    }

    /**
     * Nginx's smooth weighted round robin, run once to completion. Over one full
     * period each backend appears exactly {@code weight/gcd} times, spaced as
     * evenly as the weights allow.
     */
    private static int[] buildSchedule(List<BackendRuntime> backends) {
        List<Integer> weights = new ArrayList<>(backends.size());
        for (BackendRuntime b : backends) {
            weights.add(Math.max(0, b.weight()));
        }
        int divisor = gcd(weights);
        if (divisor == 0) {
            return new int[0]; // every backend drained
        }

        int total = 0;
        int[] w = new int[weights.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = weights.get(i) / divisor;
            total += w[i];
        }
        if (total == 0) {
            return new int[0];
        }

        int[] current = new int[w.length];
        int[] out = new int[total];
        for (int step = 0; step < total; step++) {
            int best = -1;
            for (int i = 0; i < w.length; i++) {
                if (w[i] == 0) {
                    continue;
                }
                current[i] += w[i];
                if (best < 0 || current[i] > current[best]) {
                    best = i;
                }
            }
            current[best] -= total;
            out[step] = best;
        }
        return out;
    }

    private static int gcd(List<Integer> values) {
        int g = 0;
        for (int v : values) {
            g = gcd(g, v);
        }
        return g;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
