package io.junction.balance;

import io.junction.backend.BackendRuntime;
import io.junction.backend.HealthEvent;
import io.junction.backend.HealthTracker;
import io.junction.config.BackendConfig;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Builders for balancer tests — no sockets, no health checker, just state. */
final class Backends {

    private Backends() {}

    /** Thresholds of 1 so a single event flips health in a test. */
    static BackendRuntime backend(String id, int weight) {
        return new BackendRuntime(
                new BackendConfig(id, "127.0.0.1", 8000, weight),
                new HealthTracker(1, 1, Clock.systemUTC()));
    }

    static List<BackendRuntime> equalWeight(int count) {
        List<BackendRuntime> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(backend("b" + i, 100));
        }
        return out;
    }

    static void markUnhealthy(BackendRuntime b) {
        b.healthTracker().apply(new HealthEvent.ProbeFailed("test"));
    }

    static void setInflight(BackendRuntime b, int n) {
        for (int i = 0; i < n; i++) {
            b.requestStarted();
        }
    }

    static String idOf(PickResult r) {
        return r instanceof PickResult.Chosen c ? c.backend().id() : "<none>";
    }
}
