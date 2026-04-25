# Notes — Performance & Load-Testing Glossary

A growing reference for the terms that come up while reading load-test output and Grafana
panels. Each entry: one-line definition → visual → intuition → real example from our own
runs → common misreading. Add new entries here as new terms arise.

**Index:**

| Term | What it answers |
|---|---|
| [Saturation knee](#saturation-knee) | "How much load can my service take before latency explodes?" |
| [Peak VUs (k6)](#peak-vus-k6) | "Why does k6 need 1 virtual user one minute and 3000 the next?" |
| [Latency percentiles (p50, p95, p99…)](#latency-percentiles-p50-p95-p99) | "Why don't we just look at the average?" |
| [Bimodal latency](#bimodal-latency) | "Why is p50 fast but p90 catastrophic?" |
| [Tail latency](#tail-latency) | "What's the slowest request my user experienced?" |
| [Throughput (RPS) — averages lie](#throughput-rps--averages-lie) | "Why is 'average 337 RPS' meaningless on a ramping test?" |
| [SLA and the latency budget](#sla-and-the-latency-budget) | "What does '50ms SLA' actually buy me?" |
| [Backpressure / request queueing](#backpressure--request-queueing) | "Why does latency turn from milliseconds into seconds, suddenly?" |

---

## Saturation knee

**One-line:** the RPS at which latency stops being flat and starts climbing sharply.

**Visual:**

```
latency (ms)
   │                       ┌─── steep climb (queueing past the limit)
   │                      ╱
   │                     ╱
   │                    ╱
   │                   ╱
   │  ─────────────── ╱   ◄── THE KNEE
   │  flat zone (capacity available)
   │
   └──────────────────────────────────────► RPS
                          ↑
                     saturation point
```

**Intuition.** Below the knee the system has spare capacity — every request is served
immediately. Past the knee, requests start waiting behind each other and latency
multiplies. Think of a 4-lane highway: traffic flows freely until you hit ~80% capacity,
then average speed collapses fast.

**Why it matters.** This is the most important number you can find about a service. It
sets your safe operating envelope, your autoscaling thresholds, and your alert thresholds.
You don't run production at the knee — you run at maybe 50–60% of it so you have headroom
for traffic spikes.

**Real example from our runs.**
- **Run 1 knee:** ~150 RPS. Single event-loop + sync Redis bottleneck. Past 150 RPS,
  p90 went from 3 ms → 3 seconds.
- **Run 2 knee:** not reached at 1000 RPS. The H.2 ramp test never bent the curve — we
  know the new ceiling is somewhere above 1000 RPS but not where exactly.

**How to spot it on a ramp test.** Watch the latency-percentile panel as RPS climbs.
While the line stays flat, you're under the knee. The first sign of the knee is p99
starting to lift while p50 stays low (queue affects tail first). When p90 also starts
climbing, you're well past it.

**Common misreading.** "We hit 1000 RPS without errors, so the system can do 1000 RPS."
No — error-free at 1000 RPS only means latency was acceptable at 1000 RPS *for the
duration we held that load*. Hold it longer and the queue may still grow.

---

## Peak VUs (k6)

**One-line:** the maximum number of concurrent virtual users k6 needed to deliver the
target request rate. It's a client-side proxy for server response time.

**The math:**

```
VUs needed  =  target_RPS  ×  avg_response_time_in_seconds
```

Each VU can have only one request in flight at a time. To sustain a target rate, k6
needs enough VUs so the pipeline is always full.

**Worked examples:**

| target_RPS | avg response time | VUs k6 needs |
|---|---|---|
| 1000 | 1 ms | 1000 × 0.001 = **1** |
| 1000 | 100 ms | 1000 × 0.1 = **100** |
| 1000 | 1 s | 1000 × 1 = **1,000** |
| 1000 | 3 s | 1000 × 3 = **3,000** |

**Intuition.** Imagine a coffee shop trying to serve 1000 cups per minute. If each barista
takes 1 second per cup, you need ~17 baristas. If each barista takes 1 minute per cup,
you need 1000 baristas. The number of baristas in the shop tells you exactly how slow each
one is — without watching them work.

VUs are the same thing for k6.

**Real example from our runs.**
- **Run 1 H.2 peak VUs = 3,076.** Server was so slow at peak (p90 = 3 seconds) that k6
  had to keep ~3000 requests in flight at all times to maintain the rate.
- **Run 2 H.2 peak VUs = 2.** Server is so fast (~1 ms per request) that 1 VU is enough
  for 1000 RPS; the second VU covers natural overlap.

**Why VUs are useful.** They are the **purest client-side measurement of server
response time** you can get. You don't even need server-side metrics to diagnose a slow
server — peak VU count tells you. The 1500× ratio in our VU counts is the exact mirror
of the 1500× ratio in our p90 latency.

**Common misreading.** "We configured `maxVUs: 5000` and only used 2 — the test was too
light." No — `maxVUs` is the *ceiling* k6 is allowed to grow to if it needs more
concurrency. If actual peak is far below the ceiling, the server is fast, not the test
weak. Look at RPS and percentiles to judge load magnitude, not VU count.

---

## Latency percentiles (p50, p95, p99…)

**One-line:** "p_N_" means *N percent of requests were faster than this number*.

**Visual:**

```
                                  these requests
                                   are p99-p100
   number of                          ↓
   requests          ▓                ▓
                    ▓▓▓              ▓▓
                   ▓▓▓▓▓            ▓▓▓
                  ▓▓▓▓▓▓▓          ▓▓▓▓
                 ▓▓▓▓▓▓▓▓▓        ▓▓▓▓▓
                ▓▓▓▓▓▓▓▓▓▓▓      ▓▓▓▓▓▓ ▓ ▓
   ────────────────────────────────────────────────► latency
                ↑      ↑                   ↑
              p50   p95               p99 ← (1% slowest)
            (median)
```

**The numbers in plain English.**

| Percentile | Meaning |
|---|---|
| p50 (median) | Half of requests are faster than this |
| p90 | 90% of requests are faster — only 1 in 10 is worse |
| p95 | 95% faster — 1 in 20 is worse |
| p99 | 99% faster — 1 in 100 is worse |
| p99.9 | 99.9% faster — 1 in 1000 is worse |
| max | The single slowest request |

**Why we use percentiles instead of averages.** A few catastrophic outliers can drag the
average dramatically while leaving p50 unchanged. Averages tell you about the *typical*
experience; percentiles tell you about the *worst* experience your real users actually
face.

**Why bigger percentiles matter more, not less.** If you serve 1 million requests per
day, your p99 affects 10,000 users every day, and your p99.9 affects 1,000 every day.
Those users hit reload, complain, churn. Owning the tail is what separates services that
*feel* fast from services that just *measure* fast on average.

**Real example.** Run 1 H.2 ramp:
- p50 = 3 ms ← typical request was fine
- p90 = 3,110 ms ← 1 in 10 took 3+ seconds
- p95 = 3,430 ms

If you only watched p50 you'd think the system was healthy. The p90/p95 told the real
story: half the system worked great, the other half was disastrous.

**Common misreading.** "Our average latency is 50 ms — we're fine." Calculate your p99
before believing that. With one bad outlier of 5,000 ms in 100 requests of 5 ms each,
the average is 55 ms but p99 is 5,000 ms.

---

## Bimodal latency

**One-line:** when a system has two distinct latency populations — a fast group and a
slow group — instead of one smooth distribution.

**Visual:**

```
Healthy unimodal distribution        Bimodal (sick) distribution
─────────────────────────────        ──────────────────────────────
   ▓                                    ▓                ▓
  ▓▓▓                                  ▓▓▓               ▓▓
 ▓▓▓▓▓                                 ▓▓▓▓             ▓▓▓
▓▓▓▓▓▓▓                                ▓▓▓▓             ▓▓▓
1 hump = one consistent path           ↑                ↑
                                    served            served
                                  immediately       after queue
                                  (fast path)       (slow path)
```

**Intuition.** A healthy system has one hump because every request takes roughly the same
time. Bimodal means **two different things are happening to requests**: some hit a fast
path (no queue), some get stuck behind queued work. Most of the time this means a single
shared resource (event loop, lock, connection) is saturated and acting as a serial choke
point.

**Real example.** Run 1 H.2 was textbook bimodal: p50 = 3 ms, p90 = 3,110 ms. The fast
50% of requests were served immediately by the event loop; the slow 50% waited in queue
behind the 1ms-per-request Redis blocking. Same code, same input, drastically different
outcome based on whether you hit "the queue is empty" or "the queue has 3000 things in
front of you".

**The fix.** Always identifies as a serial bottleneck. The cure is to either:
- remove the bottleneck (Run 2: cache + MGET → barely any blocking work happens)
- parallelize through it (Run 2: multi-thread event loop + worker offload)
- batch through it (Run 2: MGET turns 278 round-trips into 1)

If a bimodal distribution disappears after a fix, you removed the right bottleneck.

**Common misreading.** "p50 looks fine, ship it." Always check p90 vs p50. If they're
within 2–3× of each other you have a healthy unimodal system. If p90 is 100× p50, you
have bimodal latency and a hidden bottleneck.

---

## Tail latency

**One-line:** the slowest small percentage of requests — the right edge of the latency
histogram.

**Why "tail" is the right word.** Latency distributions almost always have a long right
tail: most requests are fast, but a few are extremely slow. The extreme right of the
histogram looks like an animal's tail.

```
                ▓
              ▓▓▓
            ▓▓▓▓▓▓
          ▓▓▓▓▓▓▓▓▓
        ▓▓▓▓▓▓▓▓▓▓▓ ▓
      ▓▓▓▓▓▓▓▓▓▓▓▓▓ ▓ ▓ ▓                                           ▓
    ─────────────────────────────────────────────────────────────────►
                                              ↑
                                          ↑ tail ↑
```

**Why it matters more than people think.**
- A user who sees a 5-second response may be 1 in 1000, but at 1M requests/day that's
  1000 angry users daily.
- Tail amplifies through dependencies: if you call 5 services in parallel and each has a
  1% chance of being slow, the chance the *whole* request is slow is ~5% (1 - 0.99⁵).
  This is called **fan-out tail amplification.**
- Industry rule of thumb (Google "Tail at Scale" paper, 2013): if your service is a
  building block called by other services, your **p99.9** is the metric that determines
  THEIR p50.

**Real example.** Run 2 H.2:
- p50 = 1 ms, p95 = 2.44 ms — looks excellent
- max = 23.6 ms — the single worst request

23.6 ms is the tail. Over 87,599 requests, that's the worst experience anyone had — and
it's still well within the 50 ms SLA. So the tail is healthy. If max were 500 ms with the
same p50/p95, the tail would be problematic even though the medians look fine.

**Where tail comes from.** GC pauses, network jitter, lock contention, cold caches,
cache evictions, occasional rebalancing, retry storms, noisy neighbours. Every one of
these is invisible at p50 and dominant at p99.9.

---

## Throughput (RPS) — averages lie

**One-line:** "Requests per second" only means what you think it means when load is
constant. On a ramp test, the displayed average tells you almost nothing.

**The trap.** k6 reports `http_reqs: 87599  336.92/s` on a 4-minute ramp. That 337 RPS
is the **total requests divided by total time** — i.e. an average across stages running
at 50, 100, 200, 500, and 1000 RPS, weighted by duration spent at each stage. The system
spent most of the test below 200 RPS. The average tells you nothing about peak behaviour.

**Visual:**

```
RPS
1000 │                              ┌──┐
     │                            ╱    │
 500 │                          ╱      ╲
     │                       ╱          │
 200 │                    ╱─┘            │
     │                 ╱─┘                ╲
 100 │              ╱─┘                    │
  50 │  ─┐    ╱──┘                          │
     │   ╲╱─┘                                ╲
     └─────────────────────────────────────────────► time
              ↑              ↑                  ↑
         time-weighted      brief peak       wind down
         "average" lives
         here, not there
```

**Real example.** Run 1 and Run 2 both show ~325 RPS average across H.2. That looks
identical. But Run 1 spent most of its peak time at saturated 150 RPS with a 30-second
queue, while Run 2 actually delivered 1000 RPS cleanly for the full 30s. The averages
are similar; the *peaks* are 6.7× different.

**How to read throughput correctly.**
1. Always look at the QPS / RPS panel in Grafana over time, not the summary number.
2. For ramp tests, mentally segment the timeline by stage and look at each stage's
   percentiles separately.
3. The number that actually matters is **the peak sustained RPS at which percentiles
   stayed acceptable**. That's your real capacity, not the test-wide average.

---

## SLA and the latency budget

**One-line:** the maximum response time you commit to for a defined fraction of requests.

In RTB our **SLA is 50 ms** — every bid response must come back within 50 ms or it's
useless (the ad exchange has already moved on). The pipeline aborts and returns
`NoBidReason.TIMEOUT` if it can't finish in time.

**The latency budget.** 50 ms is split across stages:

| Component | Typical budget | Why |
|---|---|---|
| Network in/out | 5–10 ms | TLS, ack, body transfer |
| JSON parse | <1 ms | Fast on small payloads |
| User segment lookup | 1–2 ms | Redis SMEMBERS or cache hit |
| Targeting + freq-cap | 2–5 ms | The MGET batch lives here |
| Scoring | 5–25 ms | Especially with ML — the most expensive stage |
| Ranking + budget pacing | <1 ms | Pure CPU |
| Response build | <1 ms | Streaming codec |
| **Total budget headroom** | ~10–15 ms | What's left for tail spikes |

**Why this framing matters.** When a stage's latency creeps up, you ask "how much of my
50 ms budget did this just consume?" — not "is this acceptable in isolation?" A 30 ms
ML scoring pass might look OK alone but eats 60% of your SLA, leaving nothing for the
other 7 stages.

**Real example.** Run 2 baseline shows total p99 = 4.07 ms — about 8% of the SLA.
That's **12× headroom** within budget, which means we can absorb ML scoring growing
heavier, more candidates, network jitter, or any combination, without breaking the SLA.

**How SLA shows up in code.** [BidPipeline.execute()](performance-engineering/level-9-project-rtb-bidder/java-rtb-bidder/src/main/java/com/rtb/pipeline/BidPipeline.java#L31) checks `ctx.remainingNanos()` before each stage and aborts with `TIMEOUT` if the budget is gone.

---

## Backpressure / request queueing

**One-line:** when a system can't drain incoming work as fast as it arrives, work piles
up — and that pile is what makes latency explode.

**Visual:**

```
arriving requests:    ─────●●●●●●●●●●●●●●●─────►
                                  │
                                  ▼
                       ┌─────────────────────┐
                       │   queue (growing)   │
                       │  ●●●●●●●●●●●●●●●    │
                       └──────────┬──────────┘
                                  │
                                  ▼
                          serving capacity
                          (slower than arrival)
                                  │
                                  ▼
                          completed: ●●●

When arrival rate > drain rate, the queue grows.
When the queue grows, every new request waits for everyone in front of it.
Latency = own work + queue wait time.
```

**Intuition.** Imagine a single cashier who can serve 1 customer per minute. If 2
customers arrive per minute, the line grows by 1 per minute. After 30 minutes there are
30 people in line; the 31st customer to arrive waits 30 minutes before even being served.
This is exactly what happens to requests in a saturated server.

**Why backpressure causes the saturation knee.** Below the knee, the queue stays empty —
work drains as fast as it arrives. At the knee, arrival rate matches drain rate exactly.
Above the knee, the queue grows linearly with time, and request latency grows linearly
with queue depth.

**Real example.** Run 1 H.2 held 1000 RPS for 30 seconds against a server that could
only drain ~150 RPS. Excess: 850 RPS × 30 s = ~25,500 requests piled up. A request that
arrived 25 seconds in waited behind ~21,000 others. At 150 RPS drain rate, that's 140
seconds of queue wait — which the test reported as a 3-second p90 because k6 bailed out
before the full queue drained.

**How to combat backpressure.**
1. **Increase drain rate** — make each request faster (cache, batching, etc.).
2. **Add parallelism** — multiple workers/threads/instances drain in parallel.
3. **Apply backpressure upstream** — refuse work at the door (`429 Too Many Requests`)
   instead of accepting and queueing forever. This is what production exchanges do; our
   bidder doesn't currently shed load — it accepts everything and trusts that the SLA
   timeout aborts pathological cases.
4. **Scale down to drain** — autoscale workers based on queue depth, not CPU.

**Common misreading.** "Errors are zero, so we're fine." Zero errors at high RPS only
means the work eventually completes; it does not mean it completes within SLA. Always
combine error rate with p99 latency to assess health.
