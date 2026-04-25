# Notes — Performance Concepts (Learning Log)

A growing reference of performance and systems concepts encountered while building this
bidder. Each section answers a specific "why is the system behaving this way?" question
triggered by a real test run, dashboard observation, or code change. Add new entries
whenever a fresh "huh, why?" comes up — keep them concrete (point to the run/panel that
prompted the question) rather than abstract.

**Index:**

| Concept | Triggered by |
|---|---|
| [JVM heap — why "Committed" sits at the max](#jvm-heap--why-committed-sits-at-the-max) | H.1 baseline JVM Memory panel |
| [Cold cache vs cold JIT — the spike at test start](#cold-cache-vs-cold-jit--the-spike-at-test-start) | H.1 baseline first-30s p99.9 spike |
| [k6 cumulative percentiles vs Grafana per-window percentiles](#k6-cumulative-percentiles-vs-grafana-per-window-percentiles) | H.2 ramp: k6 reported p99 = 5.95 ms but Grafana showed p99 hitting ~55 ms at peak |
| [k6 VU constraint can hide your slow tail](#k6-vu-constraint-can-hide-your-slow-tail) | H.4 stress 5K: same load, p99 = 10 ms with 100-VU cap → 64 ms with 1,250-VU cap |

---

## JVM heap — why "Committed" sits at the max

**Triggered by:** Phase 17 H.1 baseline in Grafana. The **JVM Memory (Heap)** panel
showed the "Committed" line pinned at 512 MiB throughout the test. Looked alarming at
first — was the heap full? — but it's the production pattern we explicitly chose.

### What you actually see on the dashboard

![JVM Memory (Heap) panel during H.1 baseline](../../results/screenshots/assets/jvm-memory-heap.png)

The flat horizontal band at the top is **Committed + Max** sitting on top of each other
at 512 MiB. The wavy band oscillating around 100–200 MiB is **Used** — that's the only
line tracking actual heap pressure, and it's at ~25% of the cap with plenty of headroom.

Three lines in that panel mean three different things:

| Line | What it is | Production target |
|---|---|---|
| **Heap Max** | The ceiling set by `-Xmx` | Whatever you sized for |
| **Heap Committed** | Memory the JVM has already reserved from the OS | Should equal Max in low-latency services |
| **Heap Used** | Actual live data the JVM holds | Way below Max — the only number that signals pressure |

If "used" climbs and approaches "max", that's a real concern. If "committed" sits at "max"
from startup, that's by design.

---

### Why we set `-Xms == -Xmx`

```makefile
JVM_PROD := ... -Xms512m -Xmx512m -XX:+AlwaysPreTouch ...
```

| Flag | What it does |
|---|---|
| `-Xms512m` | Initial heap size when JVM boots |
| `-Xmx512m` | Maximum heap size the JVM is allowed to grow to |
| `-XX:+AlwaysPreTouch` | At startup, write a zero to every heap page so the OS commits it immediately |

**If `Xms < Xmx`** (the JVM default behaviour), the heap grows on demand. Each "grow" event:
1. Asks the OS for more memory pages
2. Triggers a full GC to compact existing live data into the bigger space
3. Updates internal data structures

That sequence takes **tens to hundreds of milliseconds**, and during it your service is
either paused or running with degraded throughput. For a 50ms-SLA bidder, that's an SLA
violation event waiting to happen. Pinning `Xms == Xmx` removes the ability to resize, so
you get one steady heap size for the lifetime of the process.

---

### Why `+AlwaysPreTouch`

Even with a fixed-size heap, the OS by default allocates physical pages **lazily** — it
hands the JVM a virtual address range immediately, but doesn't back it with actual RAM
until the JVM tries to write to a page.

The first write to an unmapped page triggers a **page fault**: the kernel has to:
1. Find a free physical page
2. Zero it (Linux/macOS guarantee zeroed pages for security)
3. Update the process page table
4. Resume the user thread

Cost: typically **20–80 microseconds per fault**, sometimes more under memory pressure.

In a low-latency service, this fault cost shows up as latency spikes the **first time**
any heap region is used at runtime — random tail spikes for the first few seconds after
deployment, then the curve flattens. That pattern (tall p99.9 spike at test start, then
near-zero) is exactly what you saw in the H.1 baseline panel.

`+AlwaysPreTouch` makes the JVM walk every page of the heap at startup and write a zero,
which **forces the page faults to happen during JVM init** instead of during request
processing. You pay maybe 1–2 seconds of extra startup time and get a flat p99 line for
the rest of the process's life.

For a service that boots once and runs for days, this is a no-brainer.

---

### How this connects to ZGC

`-XX:+UseZGC` is also in our flags. ZGC is a low-pause garbage collector:

- Stop-the-world pauses sub-millisecond regardless of heap size (we measured 0.011ms avg
  in v1 H.4)
- Concurrent marking and relocation — the application keeps running during most GC work
- Generational since Java 24 (default; the explicit `-XX:+ZGenerational` flag was
  removed in Java 24+)

The pre-touched, fixed-size heap **is what lets ZGC stay sub-millisecond.** With a
dynamic heap, ZGC would be chasing a moving target — every resize triggers a full
collection cycle and re-init of internal data structures. With `Xms == Xmx`, ZGC initialises
once and operates on a known, stable region for the process lifetime.

---

### The "Used" line is what matters

Rule of thumb for production:

| Used / Max | What it means |
|---|---|
| Under 50% | Plenty of headroom |
| 50–70% | Steady state for a busy service — fine |
| 70–85% | GC is working hard but coping |
| Over 85% | Real memory pressure — pauses lengthen, allocation stalls appear |
| Approaches Max | Out-of-memory imminent |

Phase 17 baseline showed used at ~128 MiB / 512 MiB = **25%**. v1's most stressful run
(H.2 ramp to 1000 RPS) peaked at ~164 MiB = **32%**. We have enormous headroom and could
serve much higher load on this same heap size.

If we ever wanted to shrink the heap (containerised deployments where memory is the
billing dimension), 256 MiB would still leave us at 64% utilisation under load — viable.
We'd need to test with the larger 1000-campaign + 500K-cache footprint first because
that's where heap usage grew.

---

## Cold-cache vs cold-JIT — the spike at test start

The H.1 baseline showed a brief p99.9 spike to ~25–30 ms in the **first 30 seconds**,
then fell to flat near-zero for the remaining 90 seconds. That's two separate "cold start"
effects stacked. Both are unavoidable but bounded; both go away within seconds and don't
recur for the life of the process.

### Cold cache

Our `CachedUserSegmentRepository` is a Caffeine LRU sized 500,000 entries, TTL 60s.
At process start the cache is empty. Each user's first appearance triggers:

```
request → cache.get(userId) → miss → delegate.getSegments(userId) → Redis SMEMBERS
       ← ~1ms round-trip ← Redis returns segments ← network ←
       cache.put(userId, segments)
```

Subsequent requests for the same user return in ~100 ns from in-memory `ConcurrentHashMap`.
The transition from "everyone's a miss" to "nearly everyone's a hit" is the warmup curve.

**How to read it on the dashboard:**

| Metric | Cold | Warm |
|---|---|---|
| `cache_user_segments_hit_ratio` | climbing from ~0.0 toward 1.0 | flat near 0.99+ |
| `redis_client_command_duration_seconds{command="smembers"}` rate | high during warmup | falls dramatically |
| Pipeline stage `UserEnrichment` p50 | ~1 ms (Redis hit per request) | ~0.001 ms (cache hit) |

Warmup time scales with cache size and traffic distribution. With 1M users uniform and
100 RPS, the cache will be ~6,000 entries deep after a 60s window — only ~0.6% of unique
users have been seen. Hit ratio at that point is still climbing. That's why higher-RPS
runs (H.2 ramp) show **flatter latency throughout** — the cache fills faster relative to
the test duration.

### Cold JIT

HotSpot is a **tiered, profiling JIT compiler**. Java bytecode does not run as native
machine code immediately; it goes through stages:

| Tier | What runs | Speed |
|---|---|---|
| Interpreter | Bytecode walked instruction-by-instruction | Slowest — baseline |
| C1 (Client Compiler) | Native code, light optimisation | ~5–10× faster than interpreter |
| C2 (Server Compiler) | Native code, aggressive inlining + escape analysis + vectorisation | ~2–3× faster than C1, ~20–30× faster than interpreter |

Promotion between tiers is **invocation-count driven**:

| Threshold (default) | Triggers |
|---|---|
| Method called ~10,000 times | Eligible for C1 compilation |
| C1-compiled method called ~100,000 times | Eligible for C2 recompilation |

(These are the `-XX:CompileThreshold` / `Tier4InvocationThreshold` defaults; OpenJDK has
moved to a more adaptive heuristic but the order of magnitude is the same.)

For our hot path (`BidPipeline.execute()`, the eight stage `process()` methods, JSON
codecs) at 100 RPS:
- Each stage runs ~100 times/second
- Reaches C1 threshold (~10K calls) after ~100 seconds — well within a 2-minute test
- Reaches C2 threshold (~100K calls) after ~1000 seconds at 100 RPS, but **much faster
  at higher RPS** — at 1000 RPS, full C2 by ~100s

That's why the latency curve is so dramatic at low RPS (long warmup) but barely
visible at high RPS (warmup completes in seconds). It's also why production services
sometimes do **synthetic warmup traffic** before opening the real load balancer — burn
through the JIT thresholds with throwaway requests so real traffic never sees the cold curve.

### Observing JIT activity

If you ever want to see this happening in real time:

```bash
# Add to JVM flags during a debug run
-XX:+PrintCompilation -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining
```

You'll see a flood of lines like `1234  256 %     4   com.rtb.pipeline.BidPipeline::execute @ 18 (243 bytes)`:
- `1234` — milliseconds since JVM start
- `256` — compilation order
- `%` — on-stack-replacement (OSR), means a long-running loop got compiled mid-execution
- `4` — tier (4 = C2)
- The rest — class, method, and bytecode size

Watching this scroll past is the most concrete way to see "this method just got promoted
from interpreted to C1 to C2" in real time.

### Deoptimization — the gotcha

JIT-compiled code can be **deoptimized** back to the interpreter if the runtime decides
its assumptions were wrong. Common triggers:

- A method's profiled type assumption was violated (e.g., compiled for `ArrayList`,
  saw a `LinkedList`)
- A class hierarchy changes (rare in production, common during dev hot-reloading)
- Uncommon-trap branches actually execute (the compiler optimised the common path
  and put the rare path in a slow handler)

When this happens you see a latency spike that looks like a fresh cold start, mid-process.
Diagnose with `-XX:+PrintCompilation` looking for `made not entrant` messages. Almost
always indicates a polymorphic-callsite bug or excessive reflection/proxy use.

For our codebase: the pipeline stages are `final` classes wired at startup, no reflection
on the hot path, no proxies — deoptimization should never happen at runtime. If it does,
that's a real bug worth investigating.

---

## k6 cumulative percentiles vs Grafana per-window percentiles

**Triggered by:** Phase 17 H.2 ramp (50 → 5,000 RPS over 6m20s). k6's run summary
reported `p(99) = 5.95 ms`, well within the `< 50 ms` SLA threshold. Grafana's
Latency Percentiles panel for the same run showed **p99 reaching ~55 ms during
the 5K RPS hold stage** at 04:50–04:51, with timeouts climbing to 7–8 req/s in
the bidder's Error & Timeout Rate panel. Both numbers are correct. They answer
different questions.

### Why they differ

The two systems compute percentiles over different sample populations:

| Tool | Window | Sample population for "p99" |
|---|---|---|
| **k6 console summary** | The entire test run | Every request in the test |
| **Grafana panels** | Sliding time window (default ~30s–1min) | Requests that arrived in the last window only |

When load is **constant**, the two numbers agree — every window looks like every
other window, so cumulative ≈ per-window. When load is **variable** (ramp,
spike, burst, mixed traffic), they diverge.

### Worked example using the H.2 numbers

```
Total test:        692,525 requests over 6m20s
At 5K RPS peak:    ~50 seconds × 5,000 RPS  = ~250,000 requests
Lower-RPS stages:  ~330s × 1.4K avg RPS     = ~440,000 requests

k6 cumulative p99: top 1% across ALL samples → 6,925 worst latencies
                   ranked among 692K total → the 6,925th-slowest
                   lands at 5.95 ms because there are MANY more
                   ~1 ms samples from low-RPS stages dragging the
                   sorted list left.

Grafana per-window p99 at 5K hold: top 1% within ONLY that window
                   → 1% of ~150K samples in the window = 1,500
                   slowest requests → all came from the saturated
                   peak → median of those tails sits at ~55 ms.
```

Both are mathematically correct percentiles; they just describe different
populations. The cumulative number tells you "across the whole test, only X% of
requests were slower than Y." The per-window number tells you "while the system
was at this specific load, X% of requests at that moment were slower than Y."

### Visualising it

```
                     LATENCY OVER TIME (real shape)
   p99 (ms)
     60 │                                ┌────┐ ← Grafana per-window
        │                              ╱      ╲   sees this peak
     40 │                            ╱          │
        │                          ╱            ╲
     20 │                       ╱─┘              │
        │                    ╱─┘                  ╲
      6 │ ────────────── ╱──┘                      └─── ← k6 cumulative
        │ low-RPS stages keep most of the test's       sees the average
        │ p99 around 1–3 ms, dragging the ranked       weighted heavily
        │ list left toward shorter values              by the long tail
      0 └────────────────────────────────────────────────► time
        warmup    50 RPS   500 RPS   2.5K   5K hold   cooldown
```

The cumulative number is sitting where most of the test data lives. The
per-window number captures the moment of stress.

### Which one to trust depends on what you're asking

| Question | Use |
|---|---|
| "Did the test pass overall?" | k6 cumulative — that's what the threshold-based release gate checks |
| "What does p99 look like at production-peak load?" | Grafana per-window during the peak stage — the only honest answer |
| "Where is the saturation knee?" | Grafana per-window across stages — find the RPS at which the per-window p99 starts climbing |
| "How many users in production would see >50 ms?" | Per-window during peak hours, not cumulative across the day |

### How to keep them honest

For load tests where the workload shape varies (ramps, spikes), **don't rely on
k6 cumulative percentiles to characterise peak behaviour.** Instead:

1. **Run constant-arrival-rate tests** at fixed RPS (`make load-test-stress-5k`,
   `-10k`, `-25k`). Each holds one rate steady, so k6's cumulative number agrees
   with Grafana's per-window — both populations are the same shape.
2. **Tag warmup separately** (the stress script already does this with
   `phase=warmup` and `phase=measure`). Thresholds applied only to the measure
   window aren't diluted by the warmup ramp.
3. **Always cross-check the Grafana time-series panels** when reading a ramp
   result. If k6 says "passed" but Grafana shows a sharp peak in the latency
   panel, the test only passed because the peak was small relative to the test
   duration.

### Production analogue

This isn't a k6-specific gotcha. It applies any time you compute aggregate
percentiles across periods of varying load:

- A daily p99 from your service's metrics will look great if traffic is highly
  uneven across the day. Rush-hour-only p99 is the metric that matters.
- A monthly SLO report can show 99.99% availability while a single 5-minute
  outage during peak hours destroyed the user experience.

Same statistical phenomenon, different scale.

---

## k6 VU constraint can hide your slow tail

**Triggered by:** Phase 17 H.4 stress test at 5,000 RPS, run twice with the
only difference being the VU pool size. With `preAllocatedVUs: 100, maxVUs: 100`,
the test reported p99 = 10.42 ms and passed all thresholds. With
`preAllocatedVUs: 625, maxVUs: 1250` (same code, same load, same script logic),
the test reported p99 = 63.9 ms and **aborted on threshold breach at 22 s of
measure phase**. Both numbers are artefacts of how k6 handles its VU pool —
neither was wrong, but only one reflected actual bidder behaviour.

### Why VU sizing matters for percentile fidelity

In k6's `constant-arrival-rate` executor, the test rig commits to firing
exactly N requests per second. To do that it needs a pool of VUs (one VU = one
in-flight request slot). The math:

```
VUs needed at any moment  =  target_RPS  ×  current_response_time_in_seconds
```

If the server is fast (1 ms responses at 5 K RPS), only ~5 VUs are needed at a
time. If the server gets slow (50 ms responses at 5 K RPS), 250 VUs are needed.
**If response time spikes briefly to 100 ms, 500 VUs are needed.**

When the demand exceeds the configured `maxVUs`, k6 has only one option:
**drop the iterations it can't fire.** Dropped iterations show up in the
output as `dropped_iterations` but **do not appear in `http_req_duration`
percentile calculations** because they never produced a request.

### What gets dropped is exactly what you want to measure

Dropped iterations are not random samples. They are **specifically the
iterations that arrived during slow moments** — when the server was struggling
and VU demand was high. Those would have been the slowest requests.

By dropping them, k6 silently filters out the slow tail. The remaining samples
reflect only the periods when the server was already fast. **Your reported
p99 is the median of "what got through during good moments" — not the actual
p99 your service produces at this load.**

### Worked example using H.4 5K data

```
Same load, same code. Two runs, only VU pool differs.

Run A — preAllocatedVUs: 100, maxVUs: 100
   target: 5,000 RPS for 3 minutes  =  900,000 requests
   delivered: 898,605 measured + 1,395 dropped
   dropped iterations were the slow ones (high VU demand moments)
   reported p99 = 10.42 ms ← the FAST p99 of survivors only
   reported p95 = 2.31 ms

Run B — preAllocatedVUs: 625, maxVUs: 1,250
   target: 5,000 RPS for 3 minutes  =  900,000 requests
   delivered: every iteration fired (max ceiling 1,250 never approached for
   the 22 s before the threshold abort)
   no slow iterations dropped, full population represented in percentiles
   reported p99 = 63.9 ms ← the REAL p99 at this load
   reported p95 = 53.9 ms
```

Run B is what production users would actually experience. Run A makes the
service look 6× better than it is.

### How to size VUs correctly for stress tests

Two principles:

1. **Always over-provision.** VU memory is cheap; the real cost of
   over-provisioning is essentially zero. The cost of under-provisioning is
   silently misleading data.
2. **Plan for the worst-case response time you'd accept seeing**, not the
   typical one. If your SLA is 50 ms and you want to characterise behaviour
   up to that boundary, size for 50 ms responses, not 1 ms.

Practical formula used in [`k6-stress.js`](../load-test/k6-stress.js) after
this incident:

```javascript
// Plan for worst-case 25 ms response (covers warmup transients + tail jitter
// at the per-rate knee), with 5× safety factor.
const baseVUs = Math.max(200, Math.ceil(RATE * 0.025 * 5));
const maxVUs  = Math.max(baseVUs * 2, Math.ceil(RATE * 0.05));
```

For 5 K RPS this yields 625 / 1,250 VUs. For 50 K RPS, 6,250 / 12,500. These
are large numbers, but k6's VU footprint is small (each is a goroutine, not
a process), so the memory cost is negligible compared to the data integrity
benefit.

### How to detect that VU starvation is corrupting your numbers

Three signals in the k6 output, any of which means your numbers may be
artificially good:

| Signal | Meaning |
|---|---|
| `WARN[Xs] Insufficient VUs, reached N active VUs and cannot initialize more` | k6 explicitly tells you it hit the ceiling. Always investigate. |
| `dropped_iterations: N` with N > 0 | Some iterations were silently lost. Even small N (0.1%) can hide significant tail. |
| `vus_max: N` close to your configured maxVUs ceiling | k6 grew to its limit; might have wanted more. Bump the budget and re-run. |

If your run shows ANY of those three, **the percentiles in the same report
are not trustworthy at the tail.** Re-run with more VUs and compare. If the
percentiles change noticeably, the original numbers were artefacts. If they
stay stable, VU starvation wasn't the issue.

### Production analogue — the same principle outside k6

This is a specific instance of a general statistical bug: **measuring only
the requests that succeed in a system where some fail or are dropped silently
gives you the percentiles of survivors, not the percentiles of attempts.**

Real-world examples:

- A load balancer with a queue limit drops requests when full. If your
  monitoring only counts requests that made it through, your "p99 latency"
  reflects the easier requests — the hard ones got dropped at the LB.
- A retry-with-backoff client retries failed requests separately. If your
  metrics record only successful responses, retried-then-succeeded requests
  show only their successful retry latency, not the original failure +
  retry total.
- An OS that drops UDP packets under load. Network metrics from receivers
  reflect packets that arrived; the slow / dropped path is invisible.

The fix is always the same: **measure attempts, not just successes.** And in
the k6 case, give the test rig enough budget that it can fire every attempt.

---

## Sources / further reading

- [Aleksey Shipilëv — JVM Anatomy Quark #2: Transparent Huge Pages](https://shipilev.net/jvm/anatomy-quarks/2-transparent-huge-pages/) — practical impact of preTouch and pages
- [OpenJDK ZGC documentation](https://wiki.openjdk.org/display/zgc/Main) — sub-ms pause architecture
- [Brendan Gregg — Linux Page Faults](https://www.brendangregg.com/perf.html) — the kernel-side cost of an unhandled page reference
