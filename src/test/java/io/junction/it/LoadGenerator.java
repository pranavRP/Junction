package io.junction.it;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A closed-loop HTTP load generator: N connections, each sending the next
 * request as soon as the previous response lands.
 *
 * <p><b>Closed loop on purpose.</b> An open-loop generator offers a fixed rate
 * and queues what the server cannot take, which hides exactly the failure Phase 4
 * is about — the queue absorbs the overload and reports it as latency much later.
 * A closed loop makes offered concurrency the independent variable, which is the
 * variable admission control actually bounds.
 *
 * <p>Built on {@link RawHttp} rather than {@code java.net.http.HttpClient} for the
 * same reason the tests are: a client that manages its own connection pool would
 * decide for itself how much concurrency to offer, and that is the number under
 * test.
 */
final class LoadGenerator {

    private LoadGenerator() {}

    /**
     * One run's outcome. Served and shed latencies are kept apart because
     * averaging them together is the classic way to publish a flattering p99:
     * shedding is fast, so a proxy that sheds nearly everything reports
     * excellent latency while doing nearly nothing.
     */
    record Result(double servedRps, int served, int shed, int other,
                  long[] servedNanos, long[] shedNanos) {

        double p99ServedMs() {
            return percentileMs(servedNanos, 99);
        }

        double p50ServedMs() {
            return percentileMs(servedNanos, 50);
        }

        double p99ShedMs() {
            return percentileMs(shedNanos, 99);
        }

        private static double percentileMs(long[] sorted, int percentile) {
            if (sorted.length == 0) {
                return Double.NaN;
            }
            int i = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile / 100.0) - 1);
            return sorted[Math.max(i, 0)] / 1_000_000.0;
        }

        @Override
        public String toString() {
            return String.format("served=%d (%.0f rps, p50 %.1fms, p99 %.1fms) shed=%d (p99 %.1fms) other=%d",
                    served, servedRps, p50ServedMs(), p99ServedMs(), shed, p99ShedMs(), other);
        }
    }

    static Result run(int port, int connections, long millis, String extraHeaders) throws Exception {
        return run(port, connections, millis, extraHeaders, 0);
    }

    /**
     * @param shedBackoffMs how long a client waits after a 503 before trying
     *     again. Zero models a client that ignores {@code Retry-After} entirely,
     *     which is the worst case and what MEA-007 deliberately measures. Any
     *     non-zero value models a client that backs off at all — necessary
     *     whenever the generator shares a CPU with the proxy, because a spinning
     *     client produces tens of thousands of refusals a second and starves the
     *     served requests the run is trying to measure.
     */
    static Result run(int port, int connections, long millis, String extraHeaders,
                      long shedBackoffMs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(connections);
        CountDownLatch ready = new CountDownLatch(connections);
        CountDownLatch go = new CountDownLatch(1);
        List<Worker> workers = new ArrayList<>();

        for (int i = 0; i < connections; i++) {
            Worker w = new Worker(port, extraHeaders, ready, go, millis, shedBackoffMs);
            workers.add(w);
            pool.execute(w);
        }
        ready.await(30, TimeUnit.SECONDS);

        go.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(millis * 4 + 30_000, TimeUnit.MILLISECONDS)) {
            pool.shutdownNow();
            throw new AssertionError("load generator did not stop");
        }

        var served = new ArrayList<Long>();
        var shed = new ArrayList<Long>();
        int other = 0;
        // Throughput is summed per connection over that connection's own window,
        // not taken as total/wall-clock. Sixty-four threads do not all start on
        // the same millisecond, and dividing by the span from the first start to
        // the last finish quietly understates the rate by the stagger.
        double rps = 0;
        for (Worker w : workers) {
            served.addAll(w.served);
            shed.addAll(w.shed);
            other += w.other;
            if (w.elapsedNanos > 0) {
                rps += w.served.size() * 1_000_000_000.0 / w.elapsedNanos;
            }
        }
        return new Result(rps, served.size(), shed.size(), other,
                sortedNanos(served), sortedNanos(shed));
    }

    private static long[] sortedNanos(List<Long> values) {
        long[] out = new long[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        Arrays.sort(out);
        return out;
    }

    private static final class Worker implements Runnable {
        private final int port;
        private final String extraHeaders;
        private final CountDownLatch ready;
        private final CountDownLatch go;
        private final long millis;
        private final long shedBackoffMs;

        final List<Long> served = new ArrayList<>();
        final List<Long> shed = new ArrayList<>();
        int other;
        long elapsedNanos;

        Worker(int port, String extraHeaders, CountDownLatch ready, CountDownLatch go,
               long millis, long shedBackoffMs) {
            this.shedBackoffMs = shedBackoffMs;
            this.port = port;
            this.extraHeaders = extraHeaders;
            this.ready = ready;
            this.go = go;
            this.millis = millis;
        }

        @Override
        public void run() {
            RawHttp conn = null;
            long begin = System.nanoTime();
            try {
                // Connections are opened before the clock starts: a run that
                // measured its own TCP handshakes would be measuring the
                // generator's ramp, not the proxy.
                conn = new RawHttp(port);
                ready.countDown();
                go.await();

                begin = System.nanoTime();
                long deadline = begin + TimeUnit.MILLISECONDS.toNanos(millis);
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    conn.write("GET /load HTTP/1.1\r\nHost: h\r\n" + extraHeaders + "\r\n");
                    RawHttp.Response r = conn.readResponse();
                    long elapsed = System.nanoTime() - t0;

                    if (r.status() == 200) {
                        served.add(elapsed);
                    } else if (r.status() == 503) {
                        shed.add(elapsed);
                        if (shedBackoffMs > 0) {
                            Thread.sleep(shedBackoffMs);
                        }
                    } else {
                        other++;
                    }
                    // Junction keeps a shed connection alive only when it can;
                    // anything it closed has to be replaced to keep offering the
                    // concurrency this run claims to offer.
                    String connection = r.headers().get("connection");
                    if (connection != null && connection.equalsIgnoreCase("close")) {
                        conn.close();
                        conn = new RawHttp(port);
                    }
                }
            } catch (IOException | InterruptedException e) {
                other++;
            } finally {
                elapsedNanos = System.nanoTime() - begin;
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (IOException ignored) {
                        // the run is over; a failed close changes no measurement
                    }
                }
            }
        }
    }
}
