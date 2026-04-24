# Phase 16: Observability gap fill — the metrics real ops teams watch

## Why this phase exists

Phase 9 built the basics — RED (Rate/Errors/Duration), fill rate, per-stage latency, JVM heap, GC. Phase 14 visualized them in Grafana. Good, but not enough for a real load-test post-mortem.

Three questions a production ad-tech SRE asks every week that the old dashboard couldn't answer:

1. **"Fill rate dropped from 27% to 18% between 2pm and 3pm — why?"**  
   Old dashboard showed the drop, not the cause. `outcome="nobid"` was one lumped counter — could be a targeting regression, frequency caps biting, or campaigns out of budget. You'd SSH to a box and grep logs.

2. **"p99 latency spiked to 200ms at 5000 RPS — CPU-bound? Redis slow? GC?"**  
   Could see the spike, couldn't see WHY. In a Vert.x system, the most common cause is something blocking the event loop — a slow log flush, a synchronous Redis call, a CPU-hot parse. No metric exposed this.

3. **"We claim the hot path has zero allocations after warmup. Does it actually, under load?"**  
   Phase 11 built a BidContext pool to make this true. But there was no runtime signal to confirm the pool wasn't running empty. Zero-alloc was a design claim, not a measured guarantee.

## What was built

### 1. No-bid reason label on `bid_responses_total`

Replaced a single `outcome="nobid"` counter with one time series per `NoBidReason`:

```
bid_responses_total{outcome="bid",     reason="matched"}
bid_responses_total{outcome="nobid",   reason="NO_MATCHING_CAMPAIGN"}
bid_responses_total{outcome="nobid",   reason="ALL_FREQUENCY_CAPPED"}
bid_responses_total{outcome="nobid",   reason="BUDGET_EXHAUSTED"}
bid_responses_total{outcome="timeout", reason="TIMEOUT"}
bid_responses_total{outcome="error",   reason="INTERNAL_ERROR"}
```

Cardinality is bounded (5 enum values), safe for Prometheus. Counters for no-bid reasons are lazily created via `ConcurrentHashMap` cache — same pattern as existing per-stage timer cache.

**Backwards compatible**: existing dashboard queries filtering only on `outcome` still work. `reason` is an additional dimension.

### 2. Event-loop lag probe

`EventLoopLagProbe` schedules a trivial task on the Vert.x event loop every 100ms. Each run records the drift between expected fire time and actual execution as a `Timer`. Exposed as `event_loop_lag_seconds` with p50/p90/p99/p999.

**The key insight**: on a healthy Vert.x loop, drift is well under 1ms. Under pressure (a blocking Redis call, a CPU-hot handler, a long GC pause), drift jumps to tens or hundreds of milliseconds. This is the single strongest signal of Vert.x health — stronger than generic CPU or heap usage, because an event-loop system can be CPU-idle and still failing if ONE handler blocks.

No external dependency — this is implemented as ~60 lines using Vert.x's own `setPeriodic` + a Micrometer `Timer`. Alternative was `vertx-micrometer-metrics`, but that pulls a lot of metrics we don't need; a direct probe is leaner.

### 3. BidContext pool saturation gauges

Two gauges exposed from the composition root:

- `bid_context_pool_available` — number of contexts currently parked in the pool
- `bid_context_pool_total_created` — cumulative count of `new BidContext(...)` calls ever made

The second one is the key signal. During warmup it climbs to (roughly) the peak concurrency. After that it must plateau. If it keeps climbing under steady load, the configured pool size (default 256) is too small — the pool empties on bursts, every extra request allocates, and Phase 11's zero-alloc promise is broken.

Pool was already tracking these internally; this commit only wires them to the Prometheus registry.

### 4. Dashboard panels

Five new panels added to `rtb-bidder.json`:

| Panel | Purpose |
|---|---|
| No-Bid Reasons (per sec) | Stacked area by `reason` label — immediately attributes fill-rate drops |
| Event Loop Lag (ms) | p50/p90/p99/p999 of the probe drift |
| BidContext Pool | available + total_created; watch that total_created plateaus |
| CPU Utilization | process vs system, 0-1 |
| JVM Threads | live / daemon / peak |

CPU and thread metrics were already exposed by `ProcessorMetrics` + `JvmThreadMetrics` binders in `MetricsRegistry` — this phase only surfaced them.

## What you learn from this phase

- **Why RED alone isn't enough for event-loop systems**: Vert.x health requires a lag-probe metric that generic RED doesn't cover.
- **How to keep Prometheus label cardinality safe**: bounded enum values, pre-enumerated tag sets, never user-supplied strings.
- **Composition-root pattern for metric registration**: the pool's lifecycle is owned by `BidPipeline`, but gauge registration happens in `Application.java` where the registry lives. The pool stays unaware of metrics; the pipeline exposes a getter. Clean separation.
- **How to verify a performance claim at runtime**: benchmarks prove Phase 11's design works *in isolation*. The pool-saturation gauge proves it works *in production traffic*. Always convert performance claims into gauges.

## Test

After restart, all new series show up:

```bash
curl -s http://localhost:8080/metrics | grep -E "bid_responses_total\{.*reason|event_loop_lag|bid_context_pool"
```

Fire a few bids (`make bid` a few times) — no-bid reasons populate, event-loop lag percentiles appear, pool counters update.

Open Grafana (`http://localhost:3000`), select the dashboard, scroll past the original panels. Five new panels visible; data populates within one scrape interval (~15s).

Adversarial check: `docker pause $(docker ps -qf name=redis)` while traffic is flowing. Within seconds:
- `bid_responses_total{reason="NO_MATCHING_CAMPAIGN"}` climbs fast (segments go empty)
- `event_loop_lag` percentiles likely stay flat — Lettuce async calls don't block the loop
- `circuit_breaker_state{name="redis"}` flips to OPEN

Then `docker unpause` and watch the system recover.

## What's deferred

Not in scope for this phase, noted for later:

- **Redis command latency** as a separate timer — stage latency currently bundles Redis RTT + JSON decode + other work
- **Kafka producer queue depth + publish latency** — events are fire-and-forget; pile-up isn't visible
- **Per-campaign metrics** (fill rate, budget burn rate per `campaign_id`)
- **Win / CTR funnel gauges** — we publish the events but don't gauge the rates
- **File descriptor usage** — needs `FileDescriptorMetrics` binder

These get their own phase once baseline + stress test results show which of them is actually the next blind spot.
