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

## Measurements

*Populate as you go. Every entry needs: what was measured, the exact command,
hardware, and the number with its spread. R-44.*

### MEA-001 — Phase 0 baseline (client -> junction -> backend)
```
Date:      2026-07-24
Hardware:  Windows 11, <cpu/cores TBD>, Docker Desktop (Linux engine)
JVM:       eclipse-temurin:21 (build + runtime), Netty 4.1.115.Final
Command:   docker compose --profile load run --rm wrk
           wrk -t4 -c64 -d15s --latency http://junction:8080/
Result:    _____ RPS, p50 _____ ms, p99 _____ ms
Notes:     Throwaway aggregating proxy, 1 backend, 1 upstream conn per request.
           This is a floor, not a target — real numbers come after Phase 1.
```

### MEA-002 — Direct-to-backend baseline (the control)
*Critical: measure the backend with no proxy in front. Every later "proxy
overhead" number is meaningless without this.*
```
Result: _____ RPS, p99 _____ ms
```

### MEA-003 — Consistent-hash rebalance fraction
### MEA-004 — P2C vs. least-connections in-flight variance
### MEA-005 — Retry amplification under total backend outage
### MEA-006 — The knee: throughput and p99 vs. offered load
### MEA-007 — Shed-path latency at 5x capacity
### MEA-008 — Capacity model predicted vs. actual
### MEA-009 — 1-hour soak: heap trend, fd count
### MEA-010 — Netty vs. virtual threads (Phase 7)

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

---

## Open questions

**OPQ-001** — Shed response 503 or 429? Leaning 503 + `Retry-After`. Resolve by Phase 4.

**OPQ-002** — Per-EventLoop pools mean up to `cores x maxIdle` idle conns per
backend. Measure actual socket count in Phase 2 and decide.

**OPQ-003** — Router trie vs. sorted prefix list. Benchmark in Phase 1.

**OPQ-004** — HashedWheelTimer tick granularity (100ms vs 10ms). Measure CPU cost.

**OPQ-005** — Does the Game Day need a second person? Decide by Phase 6.

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
```
