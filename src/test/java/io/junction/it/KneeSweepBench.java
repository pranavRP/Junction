package io.junction.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * MEA-006 and MEA-007: sweep offered concurrency and print where the knee is.
 *
 * <p>Not part of the normal suite — a benchmark that runs on every build is a
 * benchmark nobody can trust, because it competes with whatever else the machine
 * is doing and fails on a busy laptop. R-44: the command below <em>is</em> the
 * measurement, so the numbers in memory.md can be reproduced rather than believed.
 *
 * <pre>{@code ./gradlew bench}</pre>
 *
 * <p><b>The caveat that has to travel with these numbers.</b> The generator, the
 * proxy and the backend share one JVM and one set of cores here, because wrk does
 * not run on Windows and the containerised path needs a Docker daemon. Absolute
 * throughput is therefore lower than MEA-011's containerised figure and is not
 * comparable to it. The <em>shape</em> — where throughput stops rising and
 * latency starts climbing — is what this measures, and the shape is what sets
 * {@code max_in_flight}.
 */
@EnabledIfSystemProperty(named = "junction.bench", matches = "true")
class KneeSweepBench {

    private static final int[] CONCURRENCY = {1, 2, 4, 8, 16, 32, 64, 128};
    private static final long WARMUP_MS = 2_000;
    private static final long RUN_MS = 4_000;
    /** R-2: five runs minimum, median and spread — never a single number. */
    private static final int RUNS = 5;

    @Test
    void sweepOfferedConcurrencyToFindTheKnee() throws Exception {
        try (ProxyHarness h = Benches.steady(java.util.function.UnaryOperator.identity())) {
            // Admission control off: this run has to find where the proxy's own
            // knee is before there is any point choosing a limit to put in front
            // of it. Measuring the knee through a limiter would just measure the
            // limiter.
            LoadGenerator.run(h.port(), 8, WARMUP_MS, "");

            System.out.println();
            System.out.println("MEA-006 — the knee (admission control off, 3 backends, no delay, "
                    + RUNS + " runs of " + RUN_MS + "ms)");
            System.out.printf("%8s %12s %18s %10s %8s%n",
                    "conns", "median rps", "spread", "median p99", "errors");
            for (int conns : CONCURRENCY) {
                double[] rps = new double[RUNS];
                double[] p99 = new double[RUNS];
                int errors = 0;
                for (int i = 0; i < RUNS; i++) {
                    LoadGenerator.Result r = LoadGenerator.run(h.port(), conns, RUN_MS, "");
                    rps[i] = r.servedRps();
                    p99[i] = r.p99ServedMs();
                    errors += r.shed() + r.other();
                }
                java.util.Arrays.sort(rps);
                java.util.Arrays.sort(p99);
                System.out.printf("%8d %12.0f %8.0f-%-9.0f %10.2f %8d%n",
                        conns, rps[RUNS / 2], rps[0], rps[RUNS - 1], p99[RUNS / 2], errors);
            }
        }
    }

    /**
     * MEA-007: what a client pays to be refused. The shed path has to be cheap or
     * admission control is just a slower way to fail.
     */
    @Test
    void shedPathLatencyAtFiveTimesTheLimit() throws Exception {
        int limit = 16;
        try (ProxyHarness h = Benches.steady(s -> ProxyHarness.withMaxInFlight(s, limit))) {

            LoadGenerator.run(h.port(), limit, WARMUP_MS, "");

            LoadGenerator.Result r = LoadGenerator.run(
                    h.port(), limit * 5, RUN_MS, "X-Chaos-Delay: 20\r\n");

            System.out.println();
            System.out.println("MEA-007 — shed-path latency at 5x the limit (max_in_flight=" + limit + ")");
            System.out.printf("  served %d at %.0f rps, p50 %.2fms, p99 %.2fms%n",
                    r.served(), r.servedRps(), r.p50ServedMs(), r.p99ServedMs());
            System.out.printf("  shed   %d, p99 %.2fms%n", r.shed(), r.p99ShedMs());
            System.out.printf("  other  %d%n", r.other());
        }
    }
}
