# Junction

An L7 HTTP/1.1 load balancer written in Java 21 and Netty, built with the
observability, SLOs, and capacity model you would need to actually be on-call
for it.

> **Status: Phase 4 of 6 complete, with one gate criterion unmet.** The proxy
> streams, routes, enforces limits, balances across a pool, health-checks its
> backends, pools upstream connections, breaks circuits on what real traffic sees,
> ramps recovering backends, bounds retries with a budget, panics rather than
> blackholes, and now sheds rather than queues. Metrics and the admin API are
> *not built yet* — see [Roadmap](#roadmap). Everything claimed below has a number
> and a command behind it; nothing is aspirational, and the one Phase 4 criterion
> that could not be measured honestly is [marked as such](#the-knee-mea-006)
> rather than reworded until it passed.

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
| `io.junction.admit` | Admission control, load shedding | **built** |
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
| ≥ 20k RPS on a dev machine | ❌ **Not met.** 14,134 median containerised; 17,055 at the knee in-JVM (MEA-006, not comparable — different harness). The 2.8× gap is **still unprofiled**: attributing it needs the containerised control MEA-011 used, which the in-JVM harness cannot produce, and running the wrong benchmark would have been guessing with a number attached. Carried to Phase 5. |
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
visible in the tests and left as-is. Phase 4 was supposed to decide whether to add
a "prefer a backend this request has not tried" pass, and decided **not to**:
nothing has yet measured it mattering, and adding an unevidenced retry path in the
phase about the proxy doing too much would have been the wrong instinct.

### The knee (MEA-006)

`./gradlew bench` sweeps offered concurrency against the proxy, five runs of four
seconds per level, and prints where throughput stops paying for itself.

```
   conns   median rps          spread   median p99
       1         2415     1386-2736         0.73ms
       2         4891     4831-5197         0.74ms
       4         7814     7450-8010         0.88ms
       8        13363    13090-14116        1.88ms
      16        17055    16722-17686        7.43ms   <- the knee
      32        15953    13522-17056       23.09ms
      64        16942    16280-17914       53.87ms
     128        17227    15172-17793      108.20ms
```

**The knee is 16 concurrent requests.** Eight times that concurrency buys **1%
more throughput and 15× the tail latency.** Every request offered past the knee
is queueing, and queueing is all it buys — Little's law read straight off a
table. This is the entire argument for admission control: a queue does not make
an overloaded system faster, it makes the wait invisible until it is a timeout.

*The load generator shares this JVM and these cores with the proxy, because wrk
does not run on Windows and the containerised path is blocked (OPQ-010). The
absolute figures are therefore not comparable to the 14,134 above, which was
containerised. What this measures is the shape.*

### The Phase 4 gate, and the part of it I could not measure

| Criterion | Result |
|---|---|
| Served throughput stays flat at overload | ✅ **Met.** 4× the knee offered, served throughput unchanged. Asserted. |
| The proxy still serves at its knee-rate service time | ✅ **Met.** p50 0.8–0.9 ms at 4× overload, identical to the knee. Asserted. |
| The surplus is refused, not queued | ✅ **Met.** Asserted. |
| p99 of served requests rises < 2× | ⚠️ **Not validated.** |

The p99 criterion is not a failure of the proxy — it is a failure of the
instrument, and it is worth being precise about which. The load generator runs in
the same JVM as the proxy. At 64 client threads on a 12-thread machine, a served
client waiting to be *scheduled* is indistinguishable from a slow proxy, and two
runs of the identical configuration produced a served p99 of 46.5 ms and 74.8 ms
— a run-to-run spread wider than the effect an assertion would be claiming. Over
the same runs p50 never moved off 0.8–0.9 ms, which is what says the proxy itself
was steady and the measurement was not.

This gate is also the one that is **not** an assertion in `./gradlew test`, unlike
every gate before it. The test task runs with a 256 MB heap and Netty leak
detection at `PARANOID` — both of which the Phase 1 gate needs — and together they
cost about 9× throughput. That does not merely make a load test slow, it makes it
invalid: with the proxy that expensive, the in-process generator never gets 32
requests in flight, so the "overload" run is not an overload. The first version of
this gate failed in a way that looked like a latency problem and was not: it shed
106 requests where it should have shed thousands. The shed count gave it away, not
the timings.

So the gate asserts what the harness can measure and prints p99 without a
threshold. Validating it needs a load generator outside the process under test:
wrk in a container, blocked on OPQ-010. **Recorded as unmet rather than reworded
into something that passes** — an assertion whose result depends on which way the
noise fell is worse than no assertion, because it reads as evidence.


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

**Admission control** — a process-wide cap on concurrently *in-flight* requests,
not on requests per second. Requests per second is a number about the client;
in-flight is a number about us, and by Little's law it is `throughput × latency`,
so one bound covers both the queueing inside the process and the memory each
request holds — and it stays true when a backend slows down, which is exactly when
an RPS figure stops being true. Enforced with `Semaphore.tryAcquire()`, checked
before routing, so a refusal costs no route lookup, no backend pick and no
upstream connection.

**There is deliberately no queue.** A queue in front of an overloaded service
turns a fast, honest 503 into a slow 503 delivered to a client that gave up two
seconds ago. MEA-006 is the price list for the queue this doesn't build.

**Load shedding** — `503` with `Retry-After` and `X-Junction-Reason:
over_capacity`. Not `429`: that says "*you* are sending too much", which is a
claim about one client that a limit on *aggregate* concurrency does not entitle us
to make — the request being shed may be the only one that client has sent all day.
This is also the one Junction-generated error that does not close the connection:
a refused request with no body has no bytes left to drain, so the connection stays
framed, and closing it would charge every shed client a TCP handshake at exactly
the load where handshakes are what you cannot afford. A refused request *with* a
body still closes.

**Timeouts, one guard per phase** — `request_timeout_ms` is armed when the request
has been fully sent, not when it starts, so a client legitimately uploading over a
slow link is no longer killed by a limit that exists to catch a silent backend.
Moving that timer alone would have opened a hole: a backend that stalls mid-upload
silences the client through Junction's own backpressure, which is precisely the
case the idle timer declines to act on. So `stall_timeout_ms` covers the upload,
firing only while `autoRead` is off — if reads are still on, the silence is the
client's and the 408 timer owns it. The two guards partition the cases instead of
racing for them. The 1 GB gate test used to need a ten-minute timeout to work
around this; that workaround is gone, and its deletion is the proof.

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
| **4** | Admission control, load shedding, backpressure tuning, find the knee | At 2× the knee, served throughput stays flat and p99 rises < 2× | ⚠️ **partial** — throughput and p50 met, p99 unvalidated |
| **5** | Prometheus/Micrometer RED metrics, structured access log, Grafana dashboards, burn-rate alerts | A stranger diagnoses an injected fault from dashboards in 5 minutes | next |
| **6** | Graceful drain, hot config reload, admin API, capacity model, runbook, Game Day | Capacity model predicts measured max RPS within ±15% | planned |
| 7 | Virtual-threads implementation benchmarked head-to-head against Netty | *optional* | planned |
| 8 | fd exhaustion, ephemeral port exhaustion, accept-queue overflow, `TCP_NODELAY`/delayed-ACK — each reproduced and documented | *optional* | planned |

**Open questions carried into Phase 5**, recorded before the work rather than
rationalised after it:

- **The knee is measured; a shippable default for it is not.** `max_in_flight`
  ships as `0` — off. 16 is this machine's number, and shipping it would shed
  traffic on any larger machine, which is a worse failure than shipping no bound.
  That leaves admission control in exactly the position slow start is in: correct,
  measured, and switched off in the config a reader will actually run. The
  difference is that this one comes with a command. Either find a defensible
  derivation, or say plainly that capacity is a per-deployment measurement and the
  proxy's job is to make finding it cheap.
- **The p99 gate needs a load generator outside the process under test.** See
  above. Blocked on the same Docker problem as everything else measurement-shaped.
- Where does the remaining 2.8× proxy penalty actually go? Still unprofiled after
  Phase 4, deliberately — the harness Phase 4 built cannot produce the control
  measurement the attribution needs. **Do not tune anything before profiling.**
- A backend that hangs *mid-response* is reported to the client as `408
  idle_timeout` — the client blamed for the backend's fault. Not a hang, just a
  wrong code and a misleading reason label, which is exactly what costs an hour
  during an incident. Phase 5's metrics should make the mislabelling visible.
- Retries still cover the connect failure only. Phase 4's answer to whether to
  widen them: **not yet** — retaining the request head is cheap, but nothing has
  measured the sent-but-unanswered case happening, and adding an unevidenced retry
  path in the phase about doing too much would have been the wrong instinct.
- Slow start ships **off by default**, and was not measured against a cold JVM in
  Phase 4. Still owed a defensible value or an admission that it is decoration.

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

**Check that the mechanism engaged before reading the numbers it produced.** The
Phase 4 gate failed for three different reasons before it measured anything real,
and every one was invisible in the timings: probes ejecting a saturated backend,
a cold-JIT baseline compared against a warm overload, and a "2× overload" the
generator could not actually deliver. The tell was never the latency — it was 106
refusals where there should have been thousands. A number that looks plausible is
the most expensive kind of wrong.

**Know which part of a measurement is the instrument.** p99 moved by 60% between
identical runs while p50 never moved at all. That gap is the whole story: the
proxy was steady and the harness was not, and no amount of re-running would have
turned that into a gate. Reporting it unmet took one paragraph; making it pass
would have taken one constant.

**Timing data is diagnostic data.** `time="0.242"` in a JUnit XML eliminated a
plausible hypothesis instantly. The habit of asking "how *fast* did it fail?"
before "why did it fail?" has paid for itself repeatedly.
