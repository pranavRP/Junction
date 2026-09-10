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

### DEC-012 … DEC-015 — **MISSING, recovered content needed**
*Flagged 2026-09-09*

These four entries were present in the working copy at the start of the Phase 4
session and are absent now. `Revert "Stop tracking memory.md"` (48630c1) restored
this file from the last commit that contained it — 55d0b7f, which predates
Phase 3 — over a working copy whose Phase 3 additions had never been committed.
Git therefore has no blob to recover them from.

Lost, by title:

- **DEC-012** — Panic mode is a selectability flag, not a `PickResult` variant
- **DEC-013** — The circuit breaker *is* the passive outlier detector
- **DEC-014** — Slow start ramps admission probability, not weight
- **DEC-015** — Retries cover the connect failure only
- **MEA-005** — Retry amplification under total backend outage *(the Phase 3
  gate: 200 client requests -> 220 upstream attempts, 1.100x)* — the body is
  gone, the stub heading remains
- **SUR-003** — the two Phase 3 integration tests that failed for reasons about
  the tests
- The **2026-09-08 Phase 3 session log entry**

The decisions themselves are all still implemented and commented in the code
(`BackendPool`, `CircuitBreaker`, `SlowStart`, `RetryBudget`), and MEA-005's
number survives in the README. What is lost is the reasoning and the costs, which
is the part this file exists for.

**Recovery:** the repo lives under OneDrive, which keeps per-file version history
— right-click `memory.md` -> Version history, or the web UI. A version from
2026-09-08 has all seven entries. This note stays until they are restored; it is
not a placeholder to write around.

---

### DEC-016 — Admission control is a concurrency limit with no queue
*Date: 2026-09-09 · Status: Accepted*

**Context.** Phase 4 needs a bound on what the proxy will accept. The two obvious
shapes are a rate limit (requests per second) and a concurrency limit (requests
in flight), and the reflex on top of either is a small queue to smooth bursts.

**Decision.** A process-wide cap on concurrently in-flight requests, enforced by
`Semaphore.tryAcquire()`, with **no queue**: over the limit a request is shed
immediately with 503.

**Why a concurrency limit.** Requests per second is a number about the client;
requests in flight is a number about us. By Little's law the in-flight count is
exactly `throughput x latency`, so one bound on it bounds both the queueing
inside the process and the memory every in-flight request holds — and it stays
true when a backend slows down, which is precisely when an RPS figure stops
being true. An operator setting an RPS limit has to guess a number that changes
underneath them; setting a concurrency limit does not.

**Why no queue.** A queue in front of an overloaded service converts a fast,
honest 503 into a slow 503 delivered to a client that gave up two seconds ago.
It is the single most common way p99 explodes past the knee, and shedding flat is
what makes the Phase 4 gate achievable at all — MEA-006 shows the latency the
queue would have charged.

**Why `Semaphore` and not an `AtomicInteger`.** `tryAcquire()` is a non-blocking
CAS that never parks a thread, so this is shared state without blocking (R-3) —
the same trade `ConnectionLimitHandler` already makes — and `release()` is
symmetric, which a hand-rolled compare-and-increment is not.

**Cost.** The permit is held from the request head until the response is fully
written, which is strictly longer than the client's view of the same request. A
closed-loop client offering exactly `max_in_flight` connections therefore sheds
at the margin. See SUR-004.

---

### DEC-017 — Shed with 503 and `Retry-After`, not 429
*Date: 2026-09-09 · Status: Accepted, resolves OPQ-001*

**Decision.** `503 Service Unavailable` with `Retry-After: 1` and
`X-Junction-Reason: over_capacity`.

**Why not 429.** 429 says "you are sending too much". That is a statement about
one client, and it is a claim we are not entitled to make: the limit is on
aggregate concurrency, and the request being shed may be the only one that client
has sent all day. 503 plus `Retry-After` says "this server, right now", which is
the true statement and the one a well-behaved client backs off on.

**Why the shed response does not close the connection.** Every other
Junction-generated error closes, because it happens mid-stream and the remaining
request bytes would be parsed as a bogus next request. A shed request with no
body has no remaining bytes, so the connection stays framed and reusable —
and closing it would charge every shed client a TCP handshake at exactly the load
where handshakes are the cost we cannot afford. A shed request that *does* carry
a body still closes: draining an upload we have already refused is worse than
dropping the connection.

**`Retry-After` is deliberately not a config knob.** It only has to be long
enough that a client's retry lands after the burst that shed it. MEA-007 shows
what happens with a client that ignores it entirely.

---

### DEC-018 — One timeout per phase: response head, then stall
*Date: 2026-09-09 · Status: Accepted, resolves OPQ-009, supersedes the Phase 1 timer*

**Context.** `request_timeout_ms` was armed when the request head went upstream
and cancelled when the response head returned, so it spanned the entire request
body upload. A client legitimately uploading over a slow link was killed by a
limit that exists to catch a silent backend. Surfaced when the 1 GB gate test
started tripping the 30s default at ~31s.

**Decision.** Split the transaction into phases with exactly one guard each.

| phase | guard | on expiry |
|---|---|---|
| request body streaming | stall check (`stall_timeout_ms`) | 504 `upstream_stalled` |
| waiting for the response head | `request_timeout_ms` | 504 `request_timeout` |
| response streaming | downstream idle timer | 408 `idle_timeout` (see OPQ-015) |

**Why the obvious fix was not enough.** Simply arming the response timeout later
leaves the upload unguarded, and unguarded is worse than mistimed: if the backend
stops draining mid-upload, our own write buffer fills, backpressure switches
downstream reads off, and the idle timer is suppressed *on purpose* because the
client's silence is our doing (SUR-002). Nothing at all would have been watching,
and the connection would have hung until one side gave up. The stall check has to
exist for the move to be safe, which is why they shipped together.

**Why the stall check re-arms against a timestamp instead of resetting per
chunk.** A 1 GB upload is roughly 130,000 chunks. A timer operation per chunk
would cost more than the transfer. The check schedules itself for the remaining
time whenever it finds recent progress, which is what `IdleStateHandler` does
internally and costs one timer operation per period rather than per message.

**Why it only fires when `autoRead` is off.** If reads are still on, the silence
is the client's and the idle timer owns it (408). The two guards partition the
cases rather than racing for them.

**Evidence.** `TimeoutIntegrationTest.aSlowUploadIsNotKilledByTheResponseTimeout`
fails against the old arming point and passes against the new one — verified by
restoring the old line, not by reasoning. `StreamingGateTest` dropped the
ten-minute timeout it needed as a workaround; that deletion is the proof.

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
### MEA-006 — The knee: throughput and p99 vs. offered load  *(Phase 4, done)*
```
Date:      2026-09-09
Hardware:  Windows 11, Intel i7-9750H (6C/12T @2.60GHz), 15.9 GB RAM
JVM:       eclipse-temurin:21 toolchain, Netty 4.1.115.Final
Command:   ./gradlew bench          (KneeSweepBench, 5 runs of 4s per level)
Setup:     3 chaos backends, P2C, probes dormant, no backend delay,
           admission control OFF - the knee has to be found before there is any
           point choosing a limit to put in front of it

   conns   median rps          spread   median p99   errors
       1         2415     1386-2736         0.73ms        0
       2         4891     4831-5197         0.74ms        0
       4         7814     7450-8010         0.88ms        0
       8        13363    13090-14116        1.88ms        0
      16        17055    16722-17686        7.43ms        0     <- the knee
      32        15953    13522-17056       23.09ms        0
      64        16942    16280-17914       53.87ms        0
     128        17227    15172-17793      108.20ms        0
```
**The knee is 16 concurrent requests.** Throughput saturates there and does not
move again: 128 concurrent buys **1% more throughput than 16, and 15x the tail**
(7.43ms -> 108.20ms). Every request offered past the knee is queueing, and
queueing is the only thing it buys. This is Little's law read off a table, and it
is the entire argument for admission control: the queue does not make the system
faster, it makes the wait invisible until it is a timeout.

**Caveat that travels with these numbers.** The load generator shares this JVM
and these cores with the proxy and the backends, because wrk does not run on
Windows and the containerised path is blocked by OPQ-010. Absolute throughput is
therefore **not comparable to MEA-011's 14,134**, which was containerised. What
this measures is the *shape*, and the shape is what sets `max_in_flight`.

**A finding from the first attempt, kept because it cost an hour.** With the
harness's fast test probes (200ms interval, 100ms timeout) the sweep produced
hundreds of 503s at 64 and 128 concurrent. Those were not overload errors: a
backend saturated by this very load answers its health probe late, so active
health checking ejected the pool mid-run. Fixture settings rather than the
shipped ones (2s/500ms) - but it does mean active health checking and throughput
measurement cannot both be switched on in one process, and a probe timeout has to
be set against the *loaded* response time, not the idle one.

### MEA-007 — Shed-path latency at 5x capacity  *(Phase 4, done)*
```
Date:      2026-09-09
Command:   ./gradlew bench   (KneeSweepBench.shedPathLatencyAtFiveTimesTheLimit)
Setup:     max_in_flight=16, 80 client connections (5x the limit), backend
           delay 20ms, and clients that ignore Retry-After completely - zero
           backoff, retry the instant they are refused
Measured:  served  1,948 at 428 rps, p50 31.95ms, p99 71.61ms
           shed   90,014, p99 41.98ms
           other       0
```
**The number that matters is 90,014.** In five seconds, 64 refused clients
generated eighteen thousand refusals a second - **42x the useful traffic** the
proxy was serving at the same time. Shedding is cheap per request; shedding
18,000 times a second while also serving is not, and the served p50 of 31.95ms
against a 20ms backend shows where the missing 12ms went.

So the shed p99 of 41.98ms is not the cost of refusing a request. It is the cost
of refusing at that rate, and it is self-inflicted by the client. This is the
concrete argument for `Retry-After` (DEC-017) and the reason the gate benchmark
models a client that honours it: a proxy cannot shed its way out of a client
population that treats a refusal as a signal to try harder.
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

### SUR-004 — a permit outlives the request the client can see
```
Date:     2026-09-09  (Phase 4, found while building the gate benchmark)
Symptom:  A closed-loop client offering exactly max_in_flight connections was
          shed on 43% of its requests. With 16 clients and 16 permits, nothing
          should ever have been refused.
Mechanism:
      The permit is taken when the request head is read and returned in
      finishRequest(), which runs immediately after the last response chunk is
      flushed. The client sees the response the instant those bytes land — a few
      microseconds before the proxy's own bookkeeping completes. A client that
      replies instantly can therefore have its next request arrive while the
      previous permit is still held.
      That alone is a small margin. What made it 43% is the feedback: a shed
      client retries with no delay, so one transient over-limit event puts a
      client into a spin loop, and a spinning client grabs the freed permit
      before a steady client — which needs a full round trip — can ask for it.
      The steady client is then shed and starts spinning too. The system is
      metastable at offered == limit.
Fix:  None in the proxy; this is correct behaviour. The permit genuinely covers
      a longer interval than the client observes, and it has to. Two consequences
      were absorbed elsewhere instead:
        - The Phase 2 admission tests wait for the count to settle rather than
          asserting it the instant a client has its response (asserting it
          immediately is asserting the race).
        - The Phase 4 gate benchmark measures its baseline with headroom, and
          says why in the test's own javadoc. That is experimental design, not a
          thumb on the scale.
Promote to FA? Not on its own. It is a good illustration of "the metric and the
      client disagree about when a request ended", and belongs in the writeup of
      the gate rather than as its own entry.
```

---

### SUR-005 — the gate could not be measured in the test JVM
```
Date:     2026-09-09  (Phase 4)
Symptom:  The Phase 4 gate failed under ./gradlew test with numbers that made no
          sense: 2,497 rps where the same code did 16,358 in the bench task, and
          only 106 shed responses at twice the configured limit — the limiter
          appeared not to be engaging at all.
Mechanism:
      The test task runs with -Xmx256m and Netty leak detection at PARANOID,
      both of which the Phase 1 gate requires. PARANOID instruments every buffer
      allocation and costs roughly 9x throughput here.
      The failure is not that it is slow. The load generator shares this JVM, so
      when the proxy is that expensive the 32 client threads never manage to hold
      32 requests in flight — they spend their time waiting for CPU, not waiting
      on the proxy. The overload run was not an overload, so the limiter had
      nothing to refuse, and the p99 rise being measured was the client's own
      scheduling delay rather than the proxy's queue.
Fix:  Moved the gate to `./gradlew bench`, which runs without leak detection and
      with a 512m heap, and recorded why in the class javadoc.
Lesson:
      A green gate in an environment that cannot produce the load is worse than
      no gate, because it reads as evidence. The tell was the shed count: 106
      refusals at twice the limit is not a slow machine, it is a machine that
      never reached the limit. Checking whether the *mechanism* engaged, before
      reading the numbers it produced, is what caught it.
Promote to FA? No — a measurement-methodology error, not a product failure. It is
      the honest reason the Phase 4 gate lives in a different Gradle task from
      every other gate, and the README says so rather than glossing it.
```

---

## Open questions

**OPQ-001** — ~~Shed response 503 or 429?~~ **RESOLVED 2026-09-09.** 503 plus
`Retry-After: 1` and `X-Junction-Reason: over_capacity`. See DEC-017: 429 is a
claim about one client that a limit on aggregate concurrency does not entitle us
to make.

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

**Phase 4 did not do this, and the reason matters.** Attributing a 2.8x penalty
needs a comparison against the direct-to-backend control on the same footing as
MEA-011, which was containerised. The in-JVM harness Phase 4 built cannot produce
that comparison — the load generator competes with the proxy for the same cores,
so any difference it measures is partly its own. Guessing was the one thing every
previous phase refused to do here, and running the wrong benchmark would have
been guessing with a number attached. *Carried to Phase 5, which needs a working
containerised path for its dashboards anyway (blocked on OPQ-010).*

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

**OPQ-009** — ~~`request_timeout_ms` is really a *total transaction* timeout.~~
**RESOLVED 2026-09-09.** Split into a response-head timeout armed when the upload
finishes and a stall check that covers the upload, so every phase has exactly one
guard. See DEC-018; the original entry is kept below because the reasoning about
why the obvious fix was insufficient is the useful part.

`request_timeout_ms` is really a *total transaction* timeout. The
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

**OPQ-011** — Retries cover the connect failure only (DEC-015). Retrying a
request that *was* sent and got no response back is safe for an idempotent method
and needs only the request head retained past the write — cheap, and it covers the
"backend accepted the connection then died" case that the connect retry misses.
*Decide in Phase 4; do not widen it to bodied requests, that direction is closed.*

**Phase 4's answer: not yet, and not for the stated reason.** Retaining the head
is indeed cheap, but the case it covers is already bounded by the breaker and the
retry budget, and Phase 4 produced no measurement showing it happening. Building
it now would be adding a retry path with no evidence behind it, in the phase whose
entire subject is what happens when the proxy does too much. *Revisit when
something measures the sent-but-unanswered case actually occurring.*

**OPQ-012** — A retry can land on the same backend that just failed, because one
failure is usually not enough to trip the breaker. Bounded by the budget so it
cannot storm, and the breaker makes it moot within a few requests, but a
"prefer a backend this request has not tried" pass would be strictly better.
*Measure whether it matters before building it — it may be pure ceremony once the
breaker is tuned.* Still unmeasured after Phase 4.

**OPQ-013** — Slow start ships **off by default** (`slow_start_ms: 0`), because
the correct ramp is a property of the backend's warm-up curve and not of the
proxy. That makes it the one Phase 3 feature with no default behaviour, and so the
one least likely to be exercised. *Measure a real cold JVM's throughput curve in
Phase 4 or 5 and put a defensible default in the shipped config, or admit it is
decoration.* Not measured in Phase 4 — and note that `max_in_flight` now ships
with exactly the same problem, for exactly the same reason (OPQ-016).

**OPQ-014** — Panic is recomputed with an O(backends) scan on every pick. Same
order the strategies already scan in, and pools here are tens of backends, so it
is almost certainly free. *If profiling in Phase 4 disagrees, recompute on health
transitions instead — but the flag write is already transition-only, so measure
before moving anything.* No profiling was run; MEA-006 shows the proxy saturating
at ~17k rps with panic disabled, which is not evidence either way.

**OPQ-015** — A backend that hangs *mid-response* — head sent, body stops, socket
left open — terminates, but through the downstream idle timer, so the client is
told `408 idle_timeout`. The client is being blamed for the backend's fault. The
phase guards from DEC-018 cover the upload and the response head; the response
body is the one stretch where the only timer running belongs to the wrong party.
*Not a hang, just a wrong status code and a misleading reason label — which is
exactly the sort of thing that costs an hour during an incident. Fix when the
metrics in Phase 5 make the mislabelling visible.*

**OPQ-016** — Is there a portable default for `max_in_flight`, or must it always
be measured? MEA-006 found this machine's knee at 16 concurrent, but that is a
property of the cores, the event-loop count and the backend, not of Junction. A
shipped 16 would shed traffic on any machine larger than this one, which is a
worse failure than shipping no bound at all. So it ships as 0, off — the same
position DEC-014's slow start is in, and open to the same criticism. The
difference is that this one now comes with a measurement and a reproducible
command. *Either find a defensible derivation — a multiple of the worker count is
the obvious candidate, and is entirely unvalidated — or state plainly that
capacity is a per-deployment measurement and that the proxy's job is to make
finding it cheap.*

**OPQ-017** — The Phase 4 gate's p99 criterion is unvalidated because the load
generator shares a JVM and a CPU with the proxy under test. With 64 client threads
on a 12-thread machine, a served client waiting to be scheduled is
indistinguishable from a slow proxy, and repeat runs of one configuration put the
served p99 at 46.5ms and 74.8ms — a spread wider than the effect being asserted,
while p50 held at 0.8-0.9ms throughout. *Needs an out-of-process generator: wrk in
a container, which is blocked on OPQ-010, or a second JVM on the host. Until then
the gate asserts throughput and p50 and reports p99 without a threshold.*

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

### 2026-09-09 — Phase 4 (Admission control, shedding, timeouts, the knee)
Did:     io.junction.admit: a process-wide in-flight cap on Semaphore.tryAcquire,
         no queue (DEC-016), wired into ProxyFrontendHandler ahead of routing so
         a refusal costs no route lookup, no pick and no upstream connection.
         Shed with 503 + Retry-After, keeping the connection alive when the
         refused request has no body to drain (DEC-017, resolves OPQ-001).
         Split the transaction timeout: response-head timer armed when the upload
         completes, plus a stall check covering the upload that fires only while
         our own backpressure holds the client silent (DEC-018, resolves OPQ-009).
         Made the write-buffer watermarks configurable and plumbed them into the
         upstream pool as well as the listener - the upload valve trips on the
         upstream buffer, so tuning only the downstream one tunes half a valve.
         Built a closed-loop load generator on RawHttp and a `bench` Gradle task.
Measured: MEA-006 (the knee: 16 concurrent, 17,055 rps, p99 7.43ms; 128 concurrent
         buys 1% more throughput and 15x the tail) and MEA-007 (the shed path
         under a client that ignores Retry-After: 18,000 refusals a second, 42x
         the useful traffic).
Gate:    PARTIAL. Throughput stays flat past the knee and p50 is unchanged under
         4x overload - both asserted in AdmissionGateBench. The p99 criterion is
         NOT validated: OPQ-017, the generator shares a CPU with the proxy and its
         run-to-run spread is wider than the effect. Recorded as unmet rather than
         reworded into something that passes.
Surprises: SUR-004 (a permit outlives the request the client can see, and a
         closed loop at exactly the limit is metastable) and SUR-005 (the gate
         could not be measured in the test JVM at all, and the tell was the shed
         count, not the timings).
Also:    Found that DEC-012..015, MEA-005, SUR-003 and the Phase 3 session log
         entry are missing from this file - see the marker in Decisions. Not
         caused by Phase 4's edits; the revert commit restored a pre-Phase-3 blob
         over uncommitted work. Flagged to the author with the OneDrive recovery
         path rather than reconstructed, because a half-remembered decision record
         is worse than an obviously missing one.
Stopped at: Phase 4 code and measurements complete, docs updated, uncommitted.
Next:    Phase 5 - Prometheus/Micrometer RED metrics, structured access log,
         Grafana dashboards, burn-rate alerts. Carry in: OPQ-007 (still
         unprofiled, needs the containerised path), OPQ-010 (OneDrive still
         breaks docker build, and it is now blocking two things), OPQ-015 (a
         mid-response backend hang is reported as 408), OPQ-016 (no defensible
         default for max_in_flight), OPQ-017 (the gate needs an out-of-process
         load generator).

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
