# Junction

An L7 HTTP/1.1 load balancer written in Java 21 and Netty, built with the
observability, SLOs, and capacity model you would need to actually be on-call
for it.

> **Status: Phase 3 of 6 complete.** The proxy streams, routes, enforces limits,
> balances across a pool, health-checks its backends, pools upstream connections,
> breaks circuits on what real traffic sees, ramps recovering backends, bounds
> retries with a budget, and panics rather than blackholes. Admission control,
> metrics and the admin API are *not built yet* — see [Roadmap](#roadmap).
> Everything claimed below has a number and a command behind it; nothing is
> aspirational.

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
| `io.junction.balance` | Round-robin, least-conn, P2C, consistent hash | **built** |
| `io.junction.backend` | Pool registry, health state machine, breaker, retry budget, slow start | **built** |
| `io.junction.pool` | Per-EventLoop upstream connection pool | **built** |
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
docker compose up -d          # junction :8080 + a three-backend pool
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

Break a backend and watch it leave the rotation, then repair it and watch it come
back. `X-Backend-Id` on each response says which one served it:

```bash
docker compose exec backend-2 sh -c 'wget -qO- localhost:8000/_chaos/unhealthy'
for i in $(seq 20); do curl -s -D- -o/dev/null http://localhost:8080/ | grep -i x-backend-id; done
docker compose exec backend-2 sh -c 'wget -qO- localhost:8000/_chaos/healthy'
```

Two independent mechanisms take it out, and which one wins depends on traffic.
With load running, the **circuit breaker** trips first, because it counts real
5xx responses and does not have to wait for anything. Idle, the **active health
probe** gets there in `interval_ms × unhealthy_threshold`. That is the whole
argument for having both.

Run the tests (this is the real gate — 205 tests, Netty leak detection at
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

### Resilience gates

Every one of these is an assertion in `./gradlew test`, not a number typed into a
document. Both run against real sockets and real backends.

| Gate | Result |
|---|---|
| **Phase 2** — kill a backend mid-load, client error rate back to 0 within 10 s | ✅ **Met.** 513 ms, 76 errors in the window. |
| **Phase 3** — total backend outage produces ≤ 1.1× normal upstream volume | ✅ **Met.** 200 client requests → 220 upstream attempts, **1.100×**. |

The Phase 3 number lands exactly on the ceiling because that is what a budget
does: with every backend down, every request wants to retry, so the budget is
saturated and 10% is spent to the token. The control case in the same test class
— retries switched off — produces exactly 1.000×, which is what shows the surplus
is retries and not some other source of upstream traffic. With `max_attempts: 3`
and no budget it would have been 3.000×.

**And the honest limitation:** the retry budget bounds amplification, it does not
make retries *smart*. A retry can land on the same dead backend it just failed
against, because the breaker may not have tripped yet on one failure. That is
visible in the tests and left as-is: preferring an untried backend is a Phase 4
change, and it is the breaker's job to make it moot.

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

**Balancing** — smooth weighted round robin as a precomputed lock-free schedule,
least-connections, P2C, and consistent hashing with bounded loads. P2C is the
default: near-optimal spread at O(1) with no shared state and no herd. Strategies
take an already-extracted string rather than an HTTP request, so the whole package
is testable without a socket.

**Health** — a sealed four-state machine per backend driven by active probes on a
dedicated control-plane thread, jittered per backend so the probes do not
synchronise into a spike. Asymmetric thresholds: slow to eject, slower to trust.

**Circuit breaking and passive outlier ejection** — one mechanism, not two. Every
request outcome feeds a per-backend breaker: connect refusals, upstream resets,
request timeouts, and 5xx count as failures; 4xx does not, because a client
sending a bad request is no evidence that the backend is unwell. The cooldown
doubles per re-open to a ceiling, and each expiry admits a bounded number of trial
requests. This exists because MEA-012 measured the gap it fills — an active probe
cannot react faster than its own interval, which left 76 client errors inside the
ejection window.

**Retry budget** — retries are capped as a fraction of request volume, not per
request, because the failure mode is aggregate: during a total outage every
request failing and every failure retrying is the proxy finishing off whatever the
outage started. `budget_percent` is validated to 0..100, so the worst an operator
can configure is a wider ceiling, never no ceiling. Retries are confined to the
connect failure, which is the only point in a streaming proxy where a retry is
honest — nothing has been sent, so nothing is replayed.

**Slow start** — a backend re-admitted by probes ramps its share from 5% to 100%
across `slow_start_ms` instead of taking its full 1/N with a cold JIT and an empty
connection pool. The ramp is on admission probability, so all four strategies ramp
without a line of change in any of them.

**Panic mode** — below `panic_percent` available backends the pool stops honouring
health and spreads across everything that has not been deliberately *drained*. A
half-dead pool would otherwise hand the survivors more than double their share and
take them down in turn. Panic ignores health, not intent: a host an operator
drained for maintenance is not conscripted back.

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
| **2** | Backend pools, balancing (RR / least-conn / P2C / consistent hash), active health checks, per-EventLoop connection pool | Kill a backend mid-load → client error rate returns to 0 within 10 s | ✅ done |
| **3** | Circuit breaker, outlier ejection, slow start, **retry budget**, panic mode | Total backend outage produces ≤ 1.1× normal upstream volume | ✅ done |
| **4** | Admission control, load shedding, backpressure tuning, find the knee | At 2× the knee, served throughput stays flat and p99 rises < 2× | next |
| **5** | Prometheus/Micrometer RED metrics, structured access log, Grafana dashboards, burn-rate alerts | A stranger diagnoses an injected fault from dashboards in 5 minutes | planned |
| **6** | Graceful drain, hot config reload, admin API, capacity model, runbook, Game Day | Capacity model predicts measured max RPS within ±15% | planned |
| 7 | Virtual-threads implementation benchmarked head-to-head against Netty | *optional* | planned |
| 8 | fd exhaustion, ephemeral port exhaustion, accept-queue overflow, `TCP_NODELAY`/delayed-ACK — each reproduced and documented | *optional* | planned |

**Open questions carried into Phase 4**, recorded before the work rather than
rationalised after it:

- Where does the remaining 2.8× proxy penalty actually go? Still unprofiled.
  Candidates are the extra userspace copy per hop, per-message flush syscalls, and
  Netty's 8 KB default chunk size. **Do not tune anything before profiling** —
  Phase 4 profiles it, and every phase so far has resisted guessing at it.
- `request_timeout_ms` is really a *total transaction* timeout: it is armed when
  the request head goes upstream, so it spans the whole request-body upload. A
  client legitimately uploading over a slow link is killed by a limit that exists
  to catch a silent backend. Fixing it needs a separate stall detector, not a
  moved timer — see OPQ-009. Phase 4 owns timeouts.
- Retries currently cover the connect failure only. Covering an idempotent request
  that was sent and got no response would need the request head retained past the
  write, which is cheap; covering one with a body would need the body buffered,
  which contradicts the streaming design. Decide whether the first is worth it.
- Slow start ships **off by default**, because the right ramp is a property of the
  backend and not of the proxy. That means the one Phase 3 feature with no default
  behaviour is also the one least likely to be exercised in a demo. Measure it
  against a real cold JVM before recommending a value.

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
