# Junction — Project Memory

Running log of decisions, measurements, surprises, and open threads. Append-only —
if a decision is reversed, add a new entry that supersedes it rather than editing
history. The reversal record is more interesting than the clean version.

**Why this file exists:** at the end of this project you will sit in an interview
and be asked "tell me about a hard technical problem you solved." The answer needs
a number, a mechanism, and a dead end you went down first. Those details evaporate
within about two weeks. This file is where they live.

**Update trigger (R-41):** end of every work session, and immediately after any
surprising measurement.

---

## Format

**Decisions:** `DEC-NNN`
**Measurements:** `MEA-NNN`
**Surprises / bugs:** `SUR-NNN` (promote to `docs/failure-analysis.md` if
substantial)
**Open questions:** `OPQ-NNN`

---

## Decisions

### DEC-001 — Netty for the data path, virtual threads as a Phase 7 comparison
*Date: 2026-07-24 (project start) · Status: Accepted*

**Context.** Java 21 offers virtual threads, which would allow a much simpler
thread-per-request blocking implementation. Netty is more complex but is what
production proxies are actually built on.

**Decision.** Netty for the primary implementation. Build a second implementation
behind the same interface in Phase 7 and benchmark both.

**Why.** Three reasons. (1) Netty forces engagement with the concepts the role
cares about — event loops, backpressure, buffer lifecycle — that virtual threads
deliberately hide. (2) "I implemented it both ways and measured" is a far stronger
claim than picking one. (3) The comparison document is itself a portfolio artifact
that essentially nobody else has.

**Cost.** Steeper learning curve, hence the Phase 0 spike to de-risk it.

---

### DEC-002 — Use Netty's HTTP codec, do not hand-roll a parser
*Date: 2026-07-24 · Status: Accepted*

**Decision.** `HttpServerCodec` / `HttpClientCodec`. Recorded as non-goal N7.

**Why.** Hand-rolling an RFC-9112-correct parser is 2+ weeks and the learning is
mostly about string handling, not systems. The scarce resource here is time, and
the concurrency and reliability work has a far better signal-per-hour ratio.

**Revisit if.** Profiling in Phase 4 shows the codec is the bottleneck.

---

### DEC-003 — Micrometer over the Prometheus Java client directly
*Date: 2026-07-24 · Status: Accepted*
Bucket control, a clean facade, and it is what a Spring shop would use.

---

### DEC-004 — No service discovery
*Date: 2026-07-24 · Status: Accepted*
Static YAML + file watch. Non-goal N4.

---

### DEC-005 — Backpressure via autoRead toggling, not an intermediate queue
*Date: 2026-07-24 · Status: Accepted*

**Decision.** Propagate flow control to the TCP layer by disabling reads on the
slow side, rather than buffering into an application queue.

**Why.** Toggling `autoRead` lets the kernel receive window close, which pushes
backpressure all the way to the original sender with no application memory
involved. This is the correct mechanism and explaining it well is a strong signal.

**Watch for.** Thrashing if watermarks are too tight. Measure in Phase 4.

---

### DEC-006 — Phase 0 spike stack: Gradle + Docker, JDK 21 in-container
*Date: 2026-07-24 · Status: Accepted*

**Context.** Dev box has JDK 17 on PATH; Docker Desktop present. Project mandates
Java 21 (R-10).

**Decision.** Gradle toolchain pinned to 21 (auto-provisioned locally via the
foojay resolver); the authoritative build/run path is Docker with a
`eclipse-temurin:21-jdk` build stage and `21-jre` runtime stage. `wrk` is not
available on Windows, so the load generator runs as a `williamyeh/wrk` container
on the compose network under a `load` profile — the same shape the k6 service
will take later.

**Why.** Keeps the local JDK version irrelevant and makes `docker compose up` the
single reproducible entry point (success metric #1). Two spike mains (proxy +
chaos backend) share one image; `$MAIN` selects which runs per service.

---

### DEC-007 — Phase 1 pins one upstream connection per downstream connection
*Date: 2026-07-26 · Status: Accepted, expected to be superseded in Phase 2*

**Context.** Phase 1 must deliver upstream keep-alive (it is a listed
deliverable) but the connection pool belongs to Phase 2. Something had to give
requests a reused upstream socket without building the pool early.

**Decision.** Each downstream connection owns exactly one upstream connection,
reused for every request on it, closed when the downstream closes.

**Why.** It is genuinely upstream keep-alive, it is a dozen lines, and it keeps
both channels on one EventLoop by construction (R-4). It also serialises
pipelined requests for free, which design.md §12.5 wants anyway.

**Cost — stated plainly.** Upstream concurrency is capped at the downstream
connection count, and an idle client holds an idle backend socket open. Both
are wrong at scale and both are exactly what Phase 2's pool fixes. Tracked as
OPQ-008.

---

### DEC-008 — Config validation accumulates every error and rejects unknown keys
*Date: 2026-07-26 · Status: Accepted*

**Decision.** Hand-rolled validation that collects all errors with field paths,
rather than an annotation-driven binder that throws on the first problem.
Unknown keys are a hard error, not a warning.

**Why.** Two operator-facing reasons. Fixing config one error per restart is a
miserable loop, and a silently ignored typo (`prt: 8080`) produces a process
that starts, listens on the wrong port, and looks healthy until traffic
arrives — the worst possible failure shape for a load balancer. Verified: a
config with five distinct faults reports all five and exits 1.

**Cost.** More code than a binder, and every new config field needs a line in
its `rejectUnknownKeys` allowlist or it is rejected. That coupling is
deliberate — it is what makes the typo check work.

---

### DEC-009 — Smooth weighted round robin as a precomputed schedule
*Date: 2026-08-09 · Status: Accepted · supersedes the approach in design.md §2.1*

**Context.** design.md carries nginx's per-backend `current` counters, mutated on
every pick. Those counters are shared across event loops, so the pseudocode is a
data race. design.md itself offers only "a striped counter or accept the
imprecision and document it".

**Decision.** Generate the identical smooth sequence once at construction and
index it with a single atomic counter. Weights are reduced by their GCD first, so
{100,100,100} costs three slots rather than three hundred.

**Why.** It produces exactly the same order, but selection becomes an array read
behind one atomic increment: no lock (R-3 bans them on the data path), no
allocation, no imprecision to document. The schedule depends only on the weights,
which are immutable for a config generation — the property that makes this work.

**Cost.** Schedule length is the sum of reduced weights, so pathological weights
like {997, 991} would allocate a large int array once. Acceptable, and bounded by
config validation capping weight at 1000.

---

### DEC-010 — Balancers take an extracted string, never an HTTP request
*Date: 2026-08-09 · Status: Accepted*

**Decision.** `Balancer.pick(String hashKey)`. Header and cookie extraction lives
in `io.junction.http`; `io.junction.balance` never sees a Netty type.

**Why.** R-11 forbids depending upward, and `http` sits above `balance`. The
practical payoff is that every strategy — including consistent hashing — is unit
tested with plain strings and no socket, which is why the 100,000-key MEA-003
measurement runs in milliseconds as an ordinary test.

---

### DEC-011 — Backends start Healthy, and weight 0 is a state-machine event
*Date: 2026-08-09 · Status: Accepted*

**Decision.** A newly constructed backend is `Healthy`. A backend configured with
weight 0 is put into `Draining` by applying a `DrainRequested` event at
construction, not by special-casing weight at each selection site.

**Why.** Starting `Unhealthy` would blackhole all traffic for a full probe
interval on every deploy. And routing weight 0 through the state machine means
"why is this backend not receiving traffic" has exactly one place to look, rather
than a health check in the balancer plus a weight check somewhere else.

---

## Measurements

*Populate as you go. Every entry needs: what was measured, the exact command,
hardware, and the number with its spread. R-44.*

### MEA-001 — Phase 0 baseline (client -> junction -> backend)
```
Date:      2026-07-26
Hardware:  Windows 11, Intel i7-9750H (6C/12T @2.60GHz), 15.9 GB RAM,
           Docker Desktop 29.6.1 (Linux engine, WSL2)
JVM:       eclipse-temurin:21 (build + runtime), Netty 4.1.115.Final
Command:   bash tools/bench/phase0-baseline.sh
           (wrk -t4 -c64 -d15s --latency http://junction:8080/, 1 warmup + 5 runs)
Runs:      780.51, 540.33, 1186.51, 787.33, 673.25 RPS
Result:    median 780 RPS  ·  spread 540–1187 (2.2x)
           p50 median 76.8ms · p90 median 207ms · p99 median 398ms
```

### MEA-002 — Direct-to-backend baseline (the control)
*Critical: measure the backend with no proxy in front. Every later "proxy
overhead" number is meaningless without this.*
```
Date/HW/JVM: same run as MEA-001
Command:   same script, url http://backend-1:8000/
Runs:      28657, 35237, 43475, 44272, 39320 RPS
Result:    median 39,320 RPS · spread 28.7k–44.3k
           p50 median 798us · p90 median 4.75ms · p99 median 17.8ms
```

**MEA-001 vs MEA-002 — the Phase 0 headline: the spike proxy costs ~50x.**

| | median RPS | median p99 |
|---|---|---|
| direct to backend | 39,320 | 17.8 ms |
| through junction  | 780 | 398 ms |
| **ratio** | **50.4x slower** | **22x worse** |

This is the number Phase 0 exists to produce, and it is deliberately bad.
Recorded per R-54 — the real number gets published, not a flattering one.

**Hypothesis (NOT yet proven — see OPQ-006):** the dominant cost is that the
spike opens a *fresh upstream TCP connection per request* and closes it in
`ProxyBackHandler.channelRead0` (`ctx.close()`). That means every client request
pays a full connect handshake, and every response leaves a socket in `TIME_WAIT`.
Supporting circumstantial evidence: the 2.2x run-to-run spread on the proxy vs
1.5x direct, which is the shape ephemeral-port/TIME_WAIT pressure produces as the
port table fills and recycles. Secondary suspects: `HttpObjectAggregator` on both
sides, and a `Bootstrap` allocated per request.

**What this predicts.** Upstream keep-alive alone (Phase 1) should recover most of
this; connection pooling (Phase 2) the rest. If it does not, the hypothesis was
wrong and that becomes a failure-analysis entry — which is the more interesting
outcome.

### MEA-003 — Consistent-hash rebalance fraction  *(Phase 2, done)*
```
Date:       2026-08-09
Command:    ./gradlew test --tests '*ConsistentHashBalancerTest*'
Setup:      5 backends, 160 vnodes each, 100,000 keys, remove 1 backend
Prediction (written BEFORE measuring): 20.00%  (= 1/N)
Measured:   19.73% remapped
            0.00% moved off a surviving backend
```
**Explanation of the gap.** 0.27pp under prediction, and the direction is the
informative part: the removed backend simply owned slightly less than a perfect
fifth of the ring. With 160 vnodes over 5 backends the arcs are uneven by a few
tenths of a percent, so its share was 19.73% rather than 20%. Every one of those
keys moved, and *nothing else did* — the 0.00% figure is the actual guarantee
being tested. A modulo scheme would have remapped roughly 80% here.

Raising vnodes would tighten the spread toward 20% at the cost of a larger ring
and slower construction. 160 is the standard figure and 0.27pp is not worth
paying to remove.

### MEA-012 — Backend ejection latency (the Phase 2 gate)
```
Date:      2026-08-09
Command:   ./gradlew test --tests '*BalancingIntegrationTest*'
Setup:     3 backends, p2c, health probe 200ms / unhealthy_threshold 2,
           one backend broken mid-load
Gate:      client error rate back to 0 within 10,000 ms
Measured:  76 client errors, error rate back to zero after 513 ms
```
513ms against a 10s gate — a 19x margin. It lands where the config predicts:
2 failed probes at 200ms intervals is ~400ms of detection, plus a partial
interval of slack. The 76 errors are requests already routed to the broken
backend before it was ejected; passive outlier detection (Phase 3) is what
shrinks that number, since active probing cannot react faster than its interval.

Verified again end-to-end under Docker with three real containers: breaking
backend-2 gave 13 errors in 200 requests, after which it received none, and
re-enabling it returned it to rotation within ~2 polling rounds.

### MEA-013 — Pooled upstream connection reuse across client connections
```
Date:      2026-08-09
Setup:     1 backend, 120 sequential fresh client connections, 1 request each
Measured:  96 of 120 (80%) inherited an already-open upstream socket
           max observed reuse depth: 5 requests on one upstream connection
```
Not 100%, and that is the design rather than a defect: pools are partitioned per
EventLoop, so a client can only inherit a socket left on the loop it happened to
land on. Netty assigns loops round-robin across ~2x cores, so the miss rate is
roughly the chance of landing on a cold loop. Directly relevant to OPQ-002.
### MEA-004 — P2C vs. least-connections in-flight variance
### MEA-005 — Retry amplification under total backend outage
### MEA-006 — The knee: throughput and p99 vs. offered load
### MEA-007 — Shed-path latency at 5x capacity
### MEA-008 — Capacity model predicted vs. actual
### MEA-009 — 1-hour soak: heap trend, fd count
### MEA-010 — Netty vs. virtual threads (Phase 7)

### MEA-011 — Phase 1 baseline, and the answer to OPQ-006
*Same script, same hardware, same load profile as MEA-001/002. The only thing
that changed is the proxy: streaming instead of aggregating, and one upstream
connection reused per downstream connection instead of one per request.*
```
Date:      2026-07-26
Hardware:  Windows 11, Intel i7-9750H (6C/12T @2.60GHz), 15.9 GB RAM,
           Docker Desktop 29.6.1 (Linux engine, WSL2)
JVM:       eclipse-temurin:21, Netty 4.1.115.Final
Command:   bash tools/bench/phase0-baseline.sh   (1 warmup + 5 runs each)

through junction: 10673, 14784, 15498, 14134, 12789 RPS
                  -> median 14,134 RPS · p50 3.25ms · p90 10.74ms · p99 27.08ms
direct control:   38895, 39543, 38964, 40711, 43387 RPS
                  -> median 39,543 RPS · p50 870us · p90 4.25ms · p99 13.29ms
```

| | Phase 0 spike | Phase 1 | direct control |
|---|---|---|---|
| median RPS | 780 | **14,134** | 39,543 |
| median p99 | 398 ms | **27.1 ms** | 13.3 ms |
| vs. direct | 50.4x slower | **2.8x slower** | — |

**OPQ-006 resolved: the hypothesis was right.** Phase 1 is **18.1x faster** than
the Phase 0 spike, and the proxy penalty fell from 50.4x to 2.8x. The dominant
cost really was connection-per-request; upstream keep-alive alone recovered
almost all of it, before any connection pooling exists.

**Caveat on attribution — do not over-claim.** Phase 1 changed *two* things at
once: it added upstream keep-alive **and** removed `HttpObjectAggregator` from
both pipelines. This measurement cannot separate them. The keep-alive share is
almost certainly dominant (a connect handshake per request is far more expensive
than an aggregation copy of a 29-byte body), but that is reasoning, not
evidence. Phase 2's pooling work should isolate it if the distinction ever
matters.

**Against the NFRs, honestly (R-54):**
- **NFR-1 (>=20k RPS): NOT met.** 14.1k median. The remaining 2.8x gap to the
  control is unexplained and unprofiled — that is Phase 4's job, not a number to
  explain away now. Also note the platform tax: this runs under Docker Desktop
  on WSL2, not bare Linux.
- **NFR-2 (p99 overhead <=3ms at 50% capacity): NOT MEASURED.** The +13.8ms
  overhead above is at *saturation*, which is a different question. Measuring
  NFR-2 needs a run at 50% of measured capacity. Not claimed either way.

---

## Surprises and bugs

*This section is the raw material for `docs/failure-analysis.md` and for interview
stories. Capture immediately — including the wrong hypotheses.*

### SUR-000 — template
```
Date:
Symptom:
What I thought it was first:
What it actually was:
How I found out (exact command):
Fix:
Verified by:
Promote to FA? y/n
```

### SUR-001 — Junction shed its own healthy request during the upstream connect
```
Date:     2026-07-26  (Phase 1, found by the gate test on its first run)
Symptom:  The 1 GB upload gate test died instantly with
          "java.net.SocketException: An established connection was aborted by
          the software in your host machine". Junction had closed the client
          connection mid-upload.

What I thought it was first — two wrong hypotheses, both plausible:
  (1) The idle timeout firing while backpressure held autoRead off. Discarded
      on the timing: idle timeout was 60s and the test XML reported
      time="0.242" — it failed in 242 milliseconds, not 60 seconds. The
      duration was the whole diagnosis; without it I would have "fixed" the
      wrong thing and the test would still have failed.
  (2) OutOfMemory from the deliberate -Xmx256m cap, i.e. the streaming claim
      being false. Discarded because an OOM surfaces as OutOfMemoryError, not
      as a peer-side connection abort.

What it actually was:
      The upstream connect is asynchronous, and I never stopped reading from
      the client while it was in flight. `inbox` — the 64-message bounded queue
      meant only as a safety net for messages the codec had already decoded in
      the current read batch — was instead absorbing a client uploading at
      memory speed. It hit its bound within milliseconds, and the overflow
      branch did exactly what it was written to do: shed with 503 and close.
      Junction rejected a perfectly healthy request because of its own
      connect latency.

How I found out:
      grep -o 'time="[0-9.]*"' build/test-results/test/TEST-*StreamingGateTest.xml
      The 0.242s runtime killed hypothesis (1) outright, which left the
      connect window as the only unguarded interval on that path.

Fix:  setAutoRead(false) on the downstream channel for the duration of the
      connect, and resumeReads() afterwards only when the upstream is both
      active and writable. The bug was reaching for a bigger buffer where the
      answer was to stop reading — which is DEC-005 restated, and I had already
      written that rule down before violating it.

Verified by:
      StreamingGateTest.oneGigabyteUploadStreamsWithoutBufferingIntoHeap
      1024 MB streamed, heap 6 MB -> 7 MB (delta +0 MB) inside a 256 MB heap,
      21.2s, zero Netty leak reports at PARANOID.

Promote to FA? YES — this is a genuine failure-analysis entry: a real bug, two
      recorded wrong hypotheses, a specific command that discriminated between
      them, and a fix that follows from a stated design principle. Write it up
      as FA-001 in docs/failure-analysis.md.
```

### SUR-002 — backpressure makes a busy connection look idle
```
Date:     2026-07-26  (Phase 1, found by inspection while fixing SUR-001)
Symptom:  None observed yet — this is a latent bug, caught by reading the
          IdleStateHandler interaction rather than by a failure.
Mechanism:
      IdleStateHandler measures elapsed time since the last read or write. When
      backpressure holds autoRead=false, no reads happen — so a client that is
      actively blocked trying to send us data looks identical to a client that
      has wandered off. The idle timer would fire and we would answer 408,
      punishing a peer for our own flow control.
Fix:  The idle handler now returns early when autoRead is false: the timer only
      counts while we are genuinely waiting on the peer.
Process note (R-22 violation, recorded rather than hidden):
      R-22 requires a failing test before the fix, and I fixed this one by
      inspection first. I added clientThatStopsMidRequestIsTimedOutWith408
      afterwards, which covers the branch that must still time out. The other
      branch — silence caused by our own backpressure, which must NOT time out —
      needs a throttled reader and is deferred to Phase 4's slow-client tests
      (FR-4.4). Recorded as a known gap, not as covered.
Promote to FA? Not yet — no observed failure. Revisit after Phase 4 proves it.
```

---

## Open questions

**OPQ-001** — Shed response 503 or 429? Leaning 503 + `Retry-After`. Resolve by Phase 4.

**OPQ-002** — Per-EventLoop pools mean up to `cores x maxIdle` idle conns per
backend. Measure actual socket count in Phase 2 and decide.

**OPQ-003** — Router trie vs. sorted prefix list. Benchmark in Phase 1.

**OPQ-004** — HashedWheelTimer tick granularity (100ms vs 10ms). Measure CPU cost.

**OPQ-005** — Does the Game Day need a second person? Decide by Phase 6.

**OPQ-006** — ~~Is the 50x Phase 0 proxy penalty actually
connection-per-request?~~ **RESOLVED 2026-07-26, hypothesis confirmed.** See
MEA-011: Phase 1 is 18.1x faster and the penalty fell 50.4x -> 2.8x. Caveat
recorded there — Phase 1 changed keep-alive *and* de-aggregation together, so
the split between them is reasoned, not measured.

**OPQ-007** — Where does the remaining 2.8x proxy penalty go? Unprofiled as of
Phase 1. Candidates: the extra userspace copy per hop, per-message flush
syscalls on the response path, Netty's 8 KB default `maxChunkSize` producing
many small HttpContent messages, and the one-upstream-connection-per-downstream
pinning limiting upstream parallelism. *Profile in Phase 4 rather than guessing;
do not tune anything before then.*

**OPQ-010** — The repo lives under `C:\Users\prana\OneDrive\...`, and OneDrive
Files-On-Demand turns untouched files into reparse-point placeholders. Docker's
BuildKit cannot read them: `docker build` failed with
`invalid file request Dockerfile` while reporting `transferring dockerfile: 31B`
for a 1009-byte file. Confirmed by copying the identical context outside OneDrive,
where it built first try. `attrib +P -U` did not clear it.

Nothing to do with the code, but it breaks the headline
`docker compose up` claim on a machine that has synced the repo and not touched
it recently — i.e. exactly the "clean machine" case in the success metrics.
*Resolve by moving the working copy off OneDrive before Phase 6, or the demo
fails for the one audience it exists for.*

**OPQ-009** — `request_timeout_ms` is really a *total transaction* timeout. The
timer is armed when the request head goes upstream and cancelled when the
response head returns, so it spans the entire request-body upload. A client
legitimately uploading a large body over a slow link is therefore killed by a
limit that exists to catch a *silent backend*. Surfaced when the 1 GB gate test
started tripping the 30s default at ~31s (it had passed at 21.2s on a faster
day) — the test was one machine-variance away from failing all along.

The obvious fix — arm the timer only once the request is fully sent — is not
sufficient on its own: if the backend stalls *mid-upload*, the upstream write
buffer fills, backpressure stops downstream reads, and the idle timer is
suppressed by the guard from SUR-002. Nothing would ever fire and the
connection would hang indefinitely. A correct design needs a separate stall
detector (no forward progress for N ms) alongside a response-head timeout.
*Resolve in the phase that does timeouts properly; do not bolt it on.*

**OPQ-008** — Phase 1 pins exactly one upstream connection per downstream
connection (DEC-007). This caps upstream concurrency at the downstream
connection count and makes an idle client hold an idle backend socket. Phase 2's
pool replaces it — confirm the pool actually improves throughput rather than
assuming, since MEA-011 shows keep-alive alone already recovered most of the gap.

---

## Session log

```
### 2026-07-24 — Phase 0 (Spike and skeleton)
Did:     Set up Gradle single module (toolchain 21, Netty 4.1.115), pinned
         wrapper 8.10.2. Wrote throwaway Netty spike: JunctionProxy (:8080,
         aggregating forward proxy) + ChaosBackend (:8000, 200 + X-Chaos-Delay).
         Dockerfile (multi-stage 21-jdk -> 21-jre via installDist) and
         docker-compose (junction + backend-1 + wrk load profile). DEC-006 logged.
Stopped at: build + run + wrk number  (updating MEA-001).
Next:    Confirm gate green, then Phase 1 — delete spike, real streaming proxy in
         io.junction.net / io.junction.http per R-11.

### 2026-07-26 — Phase 0 gate closed, then Phase 1 (Core proxy)
Did:     PHASE 0 GATE GREEN. Smoke-tested client->junction->backend->client,
         wrote tools/bench/phase0-baseline.sh (5 runs + warmup, R-2/R-44).
         MEA-001 = 780 RPS median, MEA-002 = 39,320 RPS direct. The spike was
         50x slower than the backend it fronts; logged the number honestly and
         opened OPQ-006 with a written hypothesis before testing it.

         PHASE 1 GATE GREEN. Deleted the spike. Built the real proxy across
         config / route / http / net per R-11:
          - immutable config record graph, YAML load, validation that reports
            every error with a field path and rejects unknown keys (DEC-008)
          - Router: host + longest-prefix, segment-boundary matching, sorted
            list not a trie (OPQ-003 — simplest thing until measured)
          - HeaderRewriter: hop-by-hop stripping incl. Connection-nominated
            headers, XFF append, XFP, X-Request-ID, framing restated after strip
          - streaming proxy handlers: no aggregator anywhere, chunked both
            directions, autoRead backpressure both directions (DEC-005),
            upstream keep-alive via one pinned upstream per downstream (DEC-007)
          - limits -> 431 / 414 / 413 / 408 / 504 / 502 / 404, each with a
            closed-enum X-Junction-Reason
         54 tests green (unit + real-socket integration), zero Netty leaks at
         PARANOID. Gate: 1 GB upload, heap 6->7 MB (+0 MB) inside -Xmx256m, 21s.
         The 256m cap is deliberate — buffering would OOM, so the streaming
         claim is structural rather than a threshold that could drift.

Surprises: SUR-001 — the gate test failed on its first run because Junction shed
         its own healthy request during the async upstream connect (autoRead
         left on, bounded inbox overflowed in 242ms). Two wrong hypotheses
         recorded; the test's 0.242s runtime was what discriminated. Fix was to
         stop reading, not to buffer more — DEC-005 restated.
         SUR-002 — latent: backpressure makes a busy connection look idle to
         IdleStateHandler. Fixed by inspection; R-22 violation recorded openly.

Numbers: MEA-011 — 14,134 RPS median / p99 27.1ms through Junction vs 39,543 /
         13.3ms direct. 18.1x faster than the spike; penalty 50.4x -> 2.8x.
         OPQ-006 resolved, hypothesis confirmed, attribution caveat recorded.
         NFR-1 (>=20k RPS) NOT met at 14.1k — published as-is per R-54.
         NFR-2 explicitly NOT measured (needs a 50%-of-capacity run).

Stopped at: Phase 1 complete, all gate criteria green, committed.

### 2026-08-09 — Phase 2 (Pools, balancing, health)
Did:     PHASE 2 GATE GREEN. Built the whole phase across backend / balance /
         pool and wired it into the data path:
          - config: strategy, hash_key, health block, upstream pool block, with
            validation that rejects a hash_key on a strategy that ignores it and
            a probe timeout that is not under its interval (DEC-008 style)
          - health state machine: 4 sealed states x 4 sealed events, exhaustive
            16-cell table test plus a guard asserting the table is complete
            (R-21), injected Clock (R-24). SlowStart deliberately omitted until
            Phase 3 implements the ramp
          - balancing: smooth weighted RR as a precomputed lock-free schedule
            (DEC-009), least-connections, p2c, and consistent hashing with
            bounded loads. Strategies take an extracted string, never a request
            (DEC-010)
          - active health checker on a dedicated executor, jittered per backend
          - per-EventLoop LIFO upstream connection pool with idle TTL, max idle,
            and close-eviction of pooled sockets
          - ProxyFrontendHandler now routes -> balances -> acquires -> releases,
            retiring the DEC-007 one-upstream-per-downstream pinning
         157 tests green, zero Netty leaks at PARANOID. Also verified the whole
         lifecycle under Docker with 3 real backend containers: break one, watch
         it leave rotation, repair it, watch it return.

Numbers: MEA-012 (gate) — backend killed, client error rate back to zero in
         513ms against a 10s gate. 76 errors during the window.
         MEA-003 — 19.73% of 100k keys remapped when 1 of 5 backends leaves,
         against a 20.00% prediction written first; 0.00% moved off surviving
         backends, which is the actual guarantee.
         MEA-013 — 80% of fresh client connections inherited a pooled upstream
         socket; the other 20% is per-EventLoop partitioning, not a defect.

Surprises: no new SUR entries. Two test failures during the phase were both my
         tests being wrong rather than the code: smooth WRR at {5,1,1} really
         does emit a run of 4 across the period boundary (so the weight set was
         a poor demonstration, switched to {5,5,1}), and least-connections
         really does return the first of three equally idle backends every time.
         Recording that they were test bugs matters — "fixed the test" is only
         honest when the code was verified right first.
         OPQ-010 opened: OneDrive placeholders break docker build entirely.

Stopped at: Phase 2 complete, all gate criteria green, handed over for commit.
Next:    Phase 3 — circuit breaker with bounded half-open probes and exponential
         cooldown, passive outlier ejection, slow start on re-admission (which
         inserts SlowStart into the sealed health state and will not compile
         until every switch handles it, by design), retry budget, panic mode.
         The retry-amplification test is the single most PE-relevant test in the
         repo. Carry in: OPQ-007 (unprofiled 2.8x penalty — do not tune before
         profiling), OPQ-009 (timeout spans the upload), OPQ-010 (OneDrive).
Next:    Phase 2 — BackendPool + health state machine (table-driven exhaustive
         test, R-21), balancing strategies (RR / least-conn / P2C / consistent
         hash), active health checker on the control-plane executor, and the
         real per-EventLoop upstream pool that replaces DEC-007. Open threads to
         carry in: OPQ-007 (where the 2.8x goes — do not tune before profiling),
         OPQ-008 (prove the pool beats the pinned connection).
         Also still outstanding: the other five planning docs (pr, architecture,
         design, phases, rules) live only in the chat, not on disk.
```
