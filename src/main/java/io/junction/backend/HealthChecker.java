package io.junction.backend;

import io.junction.config.HealthConfig;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Active health probes (FR-3.1).
 *
 * <p><b>Runs on the control plane, never on an event loop</b> (R-3). Probes use
 * the blocking JDK HTTP client on a dedicated scheduled executor: blocking is
 * fine here precisely because this thread is not carrying traffic. Putting
 * probes on an event loop would let a hung backend stall unrelated requests —
 * which is the failure this component exists to detect.
 *
 * <p><b>Probes are jittered per backend.</b> Without an offset, every backend is
 * probed in the same millisecond of every interval: a synchronised load spike,
 * and a correlated blind spot between spikes. Each backend is offset by a stable
 * hash of its id, so the schedule is spread but reproducible across restarts.
 */
public final class HealthChecker implements AutoCloseable {

    private final PoolRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final HttpClient client;
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    private final boolean ownsScheduler;

    public HealthChecker(PoolRegistry registry) {
        this(registry, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "junction-health");
            t.setDaemon(true);
            return t;
        }), true);
    }

    HealthChecker(PoolRegistry registry, ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1_000))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Begins probing every backend in every pool on its own jittered schedule. */
    public void start() {
        for (BackendPool pool : registry.pools()) {
            HealthConfig health = pool.config().health();
            for (BackendRuntime backend : pool.backends()) {
                long jitter = Math.floorMod(backend.id().hashCode(), Math.max(1, health.intervalMs()));
                scheduled.add(scheduler.scheduleAtFixedRate(
                        () -> probeQuietly(pool, backend),
                        jitter, health.intervalMs(), TimeUnit.MILLISECONDS));
            }
        }
    }

    /**
     * Probes one backend and applies the result. Synchronous and public so tests
     * drive transitions directly instead of waiting on a scheduler (R-23).
     *
     * @return the backend's state after the probe
     */
    public HealthState probeOnce(BackendPool pool, BackendRuntime backend) {
        HealthConfig health = pool.config().health();
        HealthEvent event;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + backend.host() + ":" + backend.port() + health.path()))
                    .timeout(Duration.ofMillis(health.timeoutMs()))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            event = response.statusCode() / 100 == 2
                    ? new HealthEvent.ProbeSucceeded()
                    : new HealthEvent.ProbeFailed("status_" + response.statusCode());
        } catch (HttpTimeoutException e) {
            event = new HealthEvent.ProbeFailed("timeout");
        } catch (ConnectException e) {
            event = new HealthEvent.ProbeFailed("connect_failure");
        } catch (IOException e) {
            event = new HealthEvent.ProbeFailed("io_error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Shutdown in progress: report nothing rather than record a spurious
            // failure that would eject a healthy backend on the way down.
            return backend.health();
        }
        return backend.healthTracker().apply(event);
    }

    private void probeQuietly(BackendPool pool, BackendRuntime backend) {
        try {
            probeOnce(pool, backend);
        } catch (RuntimeException e) {
            // A scheduled task that throws is silently cancelled forever, which
            // would stop all probing for this backend and leave it frozen in its
            // last known state. Swallowing here keeps the schedule alive.
            backend.healthTracker().apply(new HealthEvent.ProbeFailed("probe_error"));
        }
    }

    @Override
    public void close() {
        scheduled.forEach(f -> f.cancel(false));
        scheduled.clear();
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }
}
