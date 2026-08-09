package io.junction.balance;

import io.junction.backend.BackendRuntime;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Power of two choices: sample two backends at random, take the less loaded.
 *
 * <p>Near-optimal distribution at O(1) cost with no shared state and no
 * coordination between event loops. Because the two candidates are chosen
 * randomly, two loops picking simultaneously rarely agree, which is precisely
 * the herd that {@link LeastConnectionsBalancer} suffers from.
 *
 * <p>Allocation-free: rather than filtering the list into a new collection on
 * every request, unselectable candidates are skipped by scanning forward from a
 * random index. Selection is on the hot path of every request, so garbage here
 * would be garbage per request.
 */
final class P2CBalancer implements Balancer {

    private final List<BackendRuntime> backends;

    P2CBalancer(List<BackendRuntime> backends) {
        this.backends = List.copyOf(backends);
    }

    @Override
    public PickResult pick(String hashKey) {
        int n = backends.size();
        if (n == 0 || !Balancers.anySelectable(backends)) {
            return Balancers.noneAvailable(backends);
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        BackendRuntime a = selectableFrom(rnd.nextInt(n));
        if (n == 1) {
            return new PickResult.Chosen(a);
        }
        BackendRuntime b = selectableFrom(rnd.nextInt(n));

        // The two probes can land on the same backend when few are selectable;
        // that is a correct answer, not a case to retry.
        return new PickResult.Chosen(a.inflight() <= b.inflight() ? a : b);
    }

    /** First selectable backend at or after {@code start}, wrapping. */
    private BackendRuntime selectableFrom(int start) {
        int n = backends.size();
        for (int i = 0; i < n; i++) {
            BackendRuntime b = backends.get((start + i) % n);
            if (b.selectable()) {
                return b;
            }
        }
        return null; // unreachable: anySelectable() was checked first
    }

    @Override
    public String name() {
        return "p2c";
    }
}
