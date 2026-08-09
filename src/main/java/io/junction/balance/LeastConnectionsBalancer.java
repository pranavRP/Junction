package io.junction.balance;

import io.junction.backend.BackendRuntime;

import java.util.List;

/**
 * Fewest in-flight requests wins.
 *
 * <p><b>Known pathology, kept deliberately.</b> Every event loop reads the same
 * minimum at the same instant and they all pile onto that one backend — the
 * herd problem. Under concurrency this can distribute *worse* than random.
 * {@link P2CBalancer} avoids it without any coordination, which is why P2C is
 * the default and this exists mainly as the measured comparison for MEA-004.
 */
final class LeastConnectionsBalancer implements Balancer {

    private final List<BackendRuntime> backends;

    LeastConnectionsBalancer(List<BackendRuntime> backends) {
        this.backends = List.copyOf(backends);
    }

    @Override
    public PickResult pick(String hashKey) {
        BackendRuntime best = null;
        int bestInflight = Integer.MAX_VALUE;

        for (int i = 0; i < backends.size(); i++) {
            BackendRuntime b = backends.get(i);
            if (!b.selectable()) {
                continue;
            }
            int inflight = b.inflight();
            if (inflight < bestInflight) {
                best = b;
                bestInflight = inflight;
            }
        }
        return best == null ? Balancers.noneAvailable(backends) : new PickResult.Chosen(best);
    }

    @Override
    public String name() {
        return "least_connections";
    }
}
