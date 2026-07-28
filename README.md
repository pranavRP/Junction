# Junction

An L7 HTTP/1.1 load balancer written in Java 21 and Netty, built with the
observability, SLOs, and capacity model you would need to actually be on-call
for it.

> **Status: Phase 1 of 6 complete.** The proxy streams, routes, and enforces
> limits. Load balancing, health checking, and circuit breaking are *not built
> yet* — see [Roadmap](#roadmap). Everything claimed below has a number and a
> command behind it; nothing is aspirational.

---

## Architecture

```
                      ┌──────────────────────────────┐
   client ───────────▶│  JUNCTION  :8080 data        │───────▶ backend
   (wrk / curl)       │            :9090 admin (P6)  │         (chaos server)
                      └──────────────────────────────┘
```

Inside the process, per client connection:

```
 ┌──────────────────────────────────────────────────────────────────────┐
 │  Acceptor EventLoop (1)  ──accept──▶  Worker EventLoopGroup (N=cores) │
 │                                                                       │
 │   downstream pipeline                         upstream pipeline       │
 │   ┌────────────────────────┐                  ┌────────────────────┐  │
 │   │ ConnectionLimitHandler │                  │ HttpClientCodec    │  │
 │   │ HttpServerCodec        │                  │ ProxyBackendHandler│  │
 │   │ IdleStateHandler       │                  └─────────┬──────────┘  │
 │   │ LimitsHandler          │                            │             │
 │   │ ProxyFrontendHandler   │◀───── same EventLoop ──────┘             │
 │   └────────────────────────┘                                          │
 └──────────────────────────────────────────────────────────────────────┘
```

**The one structural rule everything else depends on:** a downstream connection
and its upstream connection are pinned to the **same `EventLoop`**. No
cross-thread handoff on the data path, so all per-request state lives in one
object touched by one thread — no locks, no volatile, no synchronisation cost.
`ProxyFrontendHandler` owns the entire request lifecycle;
`ProxyBackendHandler` deliberately holds no state and only forwards events to it.

### Package layout

Packages mirror the component boundaries exactly, and no package depends upward.

| Package | Owns | Status |
|---|---|---|
| `io.junction.net` | Acceptor, pipeline setup, connection cap, limit→status mapping | **built** |
| `io.junction.http` | Request lifecycle, streaming, header rewriting | **built** |
| `io.junction.route` | `Host` + path-prefix → pool name | **built** |
| `io.junction.config` | Immutable record graph, YAML parse, validation | **built** |
| `io.junction.chaos` | Controllable backend (test fixture, not part of the proxy) | **built** |
| `io.junction.balance` | Round-robin, least-conn, P2C, consistent hash | Phase 2 |
| `io.junction.backend` | Pool registry, health state machine, breaker, slow start | Phase 2–3 |
| `io.junction.pool` | Per-EventLoop upstream connection pool | Phase 2 |
| `io.junction.admit` | Admission control, load shedding | Phase 4 |
| `io.junction.obs` | Metrics, structured access log, tracing | Phase 5 |
| `io.junction.admin` | Admin HTTP API | Phase 6 |

### Streaming and backpressure

There is **no `HttpObjectAggregator` in either pipeline**. Bodies move through as
chunks and are written to the peer as they arrive. Flow control is propagated by
toggling `autoRead` on the slow side rather than buffering into an application
queue: the kernel receive window closes and the original sender is throttled with
zero application memory involved.

"We used a bounded queue" and "we propagated TCP backpressure to the sender" are
very different levels of understanding, and this is the second one.

---

## Quickstart

```bash
docker compose up -d          # junction :8080 + one chaos backend
curl -i http://localhost:8080/
```

```
HTTP/1.1 200 OK
X-Backend-Id: backend-1
X-Conn-Requests: 1
X-Request-ID: 0116f299-198e-4c60-b51e-c6f2245bd569
connection: keep-alive
```

Steer the backend per request to exercise failure paths:

```bash
curl -i -H "X-Chaos-Delay: 250"  http://localhost:8080/   # slow response
curl -i -H "X-Chaos-Status: 503" http://localhost:8080/   # error passthrough
curl -i -H "X-Chaos-Chunks: 5"   http://localhost:8080/   # chunked download
```

Run the tests (this is the real gate — 57 tests, Netty leak detection at
`PARANOID`, test JVM capped at `-Xmx256m`):

```bash
./gradlew test
```

Reproduce the benchmark below:

```bash
bash tools/bench/phase0-baseline.sh
```

---

## Headline benchmark

Median of 5 runs plus a discarded warmup, `wrk -t4 -c64 -d15s`, Intel i7-9750H
(6C/12T), Docker Desktop on WSL2, `eclipse-temurin:21`, Netty 4.1.115. Never a
single run, never a mean.

```
throughput — median RPS, higher is better

  direct to backend   ████████████████████████████████████████  39,543
  Junction (Phase 1)  ██████████████                            14,134
  Junction (spike)    █                                            780

p99 latency — median across runs, lower is better

  direct to backend    13.3 ms
  Junction (Phase 1)   27.1 ms
  Junction (spike)    398.0 ms
```

| | median RPS | spread | median p99 | vs. direct |
|---|---|---|---|---|
| Direct to backend (control) | 39,543 | 38.9k–43.4k | 13.3 ms | — |
| **Junction, Phase 1** | **14,134** | 10.7k–15.5k | 27.1 ms | **2.8× slower** |
| Junction, Phase 0 spike | 780 | 540–1,187 | 398 ms | 50.4× slower |

The control row is the point. "Proxy overhead" means nothing without measuring
the backend with no proxy in front of it, so that number gets published first.

**What changed between the two Junction rows:** the spike opened a fresh upstream
TCP connection per request. Phase 1 reuses one. That single change is worth
**18.1×**, and it collapsed the proxy penalty from 50.4× to 2.8×.

**What I cannot claim from this:** Phase 1 changed *two* things at once — upstream
keep-alive *and* removing body aggregation. This benchmark cannot separate them.
The keep-alive share is almost certainly dominant, but that is reasoning, not
evidence, and it is recorded as such.

### Against the stated targets, honestly

| Target | Result |
|---|---|
| ≥ 20k RPS on a dev machine | ❌ **Not met.** 14,134 median. The remaining 2.8× gap is unprofiled — Phase 4's job. Guessing at it now would be the wrong move. |
| p99 overhead ≤ 3 ms at 50% capacity | ⚠️ **Not measured.** The +13.8 ms above is at *saturation*, which is a different question. Not claimed either way. |
| 1 GB upload, heap growth < 50 MB | ✅ **Met.** 1024 MB streamed, heap 6 MB → 7 MB (delta +0 MB), 21.2 s. |
| Zero Netty leaks at `PARANOID` | ✅ **Met.** |

The heap result is enforced structurally, not by a threshold: the test JVM runs
with `-Xmx256m`, so an implementation that buffered a 1 GB body would die with
`OutOfMemoryError` rather than quietly drifting past an assertion.

---

## What is built

**Proxying** — HTTP/1.1 terminate, route, forward, stream back. Chunked encoding
both directions. Client keep-alive and upstream keep-alive. Hop-by-hop header
stripping including headers *nominated by* the `Connection` field (RFC 9110
§7.6.1), which is the part that is easy to miss. `X-Forwarded-For` appended
rather than overwritten, `X-Forwarded-Proto`, `X-Request-ID` generated when
absent and echoed on the response.

**Routing** — `Host` + longest path prefix, matching only on segment boundaries
so `/api` never captures `/apifoo`. A sorted list, not a trie: with under ~20
routes a linear scan wins on cache locality and is a fraction of the code. The
trie is what you reach for *after* measuring.

**Limits, each with its own status code and a closed-enum reason label** —
`431` headers, `414` URI, `413` body, `408` idle client, `504` slow backend,
`502` connect failure, `404` no route.

**Config** — immutable record graph, validated at startup. Every error is
reported in one pass with a field path, and unknown keys are rejected outright:

```
junction: refusing to start — /tmp/bad.yaml
Invalid configuration (5 errors):
  - server has unknown key 'prt' (known: admin_port, backlog, ...)
  - server.port must be 1..65535, got 99999
  - pools[0].backends[0].port must be 1..65535, got 0
  - routes[0].prefix must start with '/', got 'relative'
  - routes[0].pool refers to unknown pool 'nope' (known: api)
```

A silently ignored typo like `prt: 8080` is worse than a hard failure — the
process starts, listens on the wrong port, and looks healthy until traffic
arrives.

---

## Roadmap

Phases 0–6 are the minimum shippable project. Each has a gate that must be green
before the next begins.

| Phase | Scope | Gate | Status |
|---|---|---|---|
| **0** | Netty spike, chaos backend, compose | One request end-to-end + one number on disk | ✅ done |
| **1** | Core streaming proxy, routing, limits, config | 1 GB upload under 50 MB heap growth, zero leaks | ✅ done |
| **2** | Backend pools, balancing (RR / least-conn / P2C / consistent hash), active health checks, per-EventLoop connection pool | Kill a backend mid-load → client error rate returns to 0 within 10 s | next |
| **3** | Circuit breaker, outlier ejection, slow start, **retry budget**, panic mode | Total backend outage produces ≤ 1.1× normal upstream volume | planned |
| **4** | Admission control, load shedding, backpressure tuning, find the knee | At 2× the knee, served throughput stays flat and p99 rises < 2× | planned |
| **5** | Prometheus/Micrometer RED metrics, structured access log, Grafana dashboards, burn-rate alerts | A stranger diagnoses an injected fault from dashboards in 5 minutes | planned |
| **6** | Graceful drain, hot config reload, admin API, capacity model, runbook, Game Day | Capacity model predicts measured max RPS within ±15% | planned |
| 7 | Virtual-threads implementation benchmarked head-to-head against Netty | *optional* | planned |
| 8 | fd exhaustion, ephemeral port exhaustion, accept-queue overflow, `TCP_NODELAY`/delayed-ACK — each reproduced and documented | *optional* | planned |

**Open questions carried into Phase 2**, recorded before the work rather than
rationalised after it:

- Where does the remaining 2.8× proxy penalty actually go? Unprofiled. Candidates
  are the extra userspace copy per hop, per-message flush syscalls, Netty's 8 KB
  default chunk size, and the current one-upstream-per-downstream pinning. **Do
  not tune anything before profiling.**
- Phase 1 pins exactly one upstream connection per downstream connection. Phase 2
  replaces it with a real pool — but keep-alive alone already recovered most of
  the gap, so the pool must be *proven* to help, not assumed to.
- Per-EventLoop pools mean up to `cores × maxIdle` idle sockets per backend. With
  8 cores and maxIdle 64 that is 512. Measure before deciding if it is acceptable.

---

## Non-goals

Explicitly out of scope. Scope discipline is itself the signal — knowing what not
to build matters as much as building it.

- **TLS termination** in v1; mTLS never.
- **HTTP/2 and HTTP/3.** h2 multiplexing breaks the connection-pool model this is
  built around, and that deserves a design document rather than a rushed feature.
- **Being faster than nginx or Envoy.** Junction will lose. The benchmark section
  says so with the number. Owning that is more credible than hiding it.
- **Service mesh, xDS, service discovery.** Static config plus a file watch only.
- **WebSocket / `CONNECT` tunnelling.** Rejected with 501.
- **Multi-tenancy, auth, per-key rate limiting.**
- **A hand-rolled HTTP parser.** `HttpServerCodec` instead. An RFC-9112-correct
  parser is two weeks of string handling; the concurrency and reliability work has
  a far better signal-per-hour ratio. This is a deliberate trade, not an oversight.

---

## Failure analysis

The most useful artifact in this repo is not the proxy — it is the record of
where it broke and why. Entries are captured the day the bug is found, wrong
hypotheses included, because a clean story with no dead ends reads as fabricated.

**[`memory.md`](memory.md)** holds the running log: decisions with their costs,
measurements with their spread, surprises, and open questions.

Worked example — **SUR-001**, found by the 1 GB gate test on its first run:

> **Symptom:** the upload died instantly with a peer-side connection abort.
> **First hypothesis (wrong):** the idle timeout firing during backpressure.
> Killed by the test's own runtime — it failed in **242 ms**, and the idle
> timeout was 60 s. That single number was the whole diagnosis; without it I
> would have fixed the wrong thing.
> **Second hypothesis (wrong):** OOM from the deliberate 256 MB heap cap. An OOM
> surfaces as `OutOfMemoryError`, not a connection abort.
> **Actual cause:** the upstream connect is asynchronous and I never stopped
> reading during it. The bounded pending queue — meant only as a safety net for
> messages already decoded — instead absorbed a client uploading at memory speed,
> hit its limit in milliseconds, and shed a perfectly healthy request with a 503.
> **Fix:** stop reading for the connect window. The bug was reaching for a bigger
> buffer where the answer was to stop reading — a principle I had already written
> down before violating it.

`docs/failure-analysis.md` is a Phase 6 deliverable requiring at least three
entries. One is ready. It is not written yet, and this README will not pretend
otherwise.

---

## What I learned

**A control measurement is worth more than the measurement.** The 14,134 RPS
number is meaningless alone. Against 39,543 direct, it is a 2.8× penalty with a
clear next question. Measuring the system with no proxy in front of it was the
highest-value 90 seconds of the project.

**Write the hypothesis down before measuring it.** The 50× spike penalty had a
predicted cause recorded in `memory.md` *before* Phase 1 began. Being able to say
"I predicted connection-per-request, measured 18.1× recovery, and here is the
part I still cannot attribute" is a stronger claim than any number alone.

**The bounded-queue reflex is usually wrong.** Twice now the instinct was to size
a buffer, and twice the correct answer was to stop reading and let TCP propagate
the pressure. Writing the rule down did not stop me from violating it; the test
did.

**Test constraints beat test assertions.** `-Xmx256m` proves the streaming claim
in a way `assertTrue(growth < 50MB)` never could. An assertion can drift as the
code changes; a heap that cannot hold the payload cannot.

**Timing data is diagnostic data.** `time="0.242"` in a JUnit XML eliminated a
plausible hypothesis instantly. The habit of asking "how *fast* did it fail?"
before "why did it fail?" has paid for itself repeatedly.
