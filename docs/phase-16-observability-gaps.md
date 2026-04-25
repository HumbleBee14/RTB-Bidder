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
- `bid_context_pool_allocated` — cumulative count of `new BidContext(...)` calls ever made

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

## Second pass — full production observability stack

After the initial gap-fill above, we expanded scope to match what a real
ad-tech production stack watches. Everything below is also part of Phase 16.

### Infrastructure exporters (4 new services)

| Service | Image | Port | What it exposes |
|---|---|---|---|
| `redis-exporter` | oliver006/redis_exporter:v1.62.0 | 9121 | Redis memory, connected clients, per-command latency, evictions, keyspace hits/misses |
| `kafka-exporter` | danielqsj/kafka-exporter:v1.8.0 | 9308 | Broker-side topic lag, partition throughput, consumer group lag, under-replicated partitions |
| `postgres-exporter` | prometheuscommunity/postgres-exporter:v0.15.0 | 9187 | Query rate, active connections, commits/rollbacks, cache hit ratio |
| `cadvisor` | gcr.io/cadvisor/cadvisor:v0.49.1 | 8081 | Per-container CPU, memory, network RX/TX, disk IO for every service |

Each has its own scrape config in `prometheus.yml` and a dedicated Grafana
section with 3-5 panels.

### Bidder-side instrumentation

**Redis client command latency** — `RedisUserSegmentRepository` and
`RedisFrequencyCapper` now wrap Lettuce calls in a `Timer` tagged
`command={smembers,get,eval}`. New metric:
`redis_client_command_duration_seconds`. Together with the server-side
metrics from `redis-exporter` and the existing stage latency, you get three
views of Redis performance and can triangulate where slowness lives.

**Kafka producer client metrics** — `KafkaEventPublisher` binds Micrometer's
`KafkaClientMetrics` to the producer instance. Exposes `kafka_producer_*`
series including send rate, batch size, retry count, buffer exhaustion,
per-topic throughput. Closed on shutdown before the producer.

**Ad funnel counters and gauges** — `BidMetrics` now tracks the full lifecycle:

```
bid → win → impression → click
```

New counters: `wins_total`, `impressions_total`, `clicks_total`.
New gauges: `win_rate = wins/bids`, `ctr = clicks/impressions`.
`WinHandler.recordWin()` fires on win notifications, `TrackingHandler`
records impressions and clicks.

**Per-campaign metrics** — `BidMetrics.recordCampaignBid(campaignId)` and
`recordCampaignWin(campaignId)` emit `campaign_bids_total` and
`campaign_wins_total` with `campaign_id` as a label. Cardinality bounded
by campaign count (10-100 in production) — safe for Prometheus.
`BidRequestHandler` records per-winner; `WinHandler` records per-win.

### Dashboard extensions

Added new sections in Grafana, each with its own row header:

- **Redis** — client-side latency, memory, clients, ops/sec per command, keyspace hits/misses, evictions
- **Kafka** — client send/retry/error rate, broker topic throughput, consumer lag, under-replicated partitions
- **PostgreSQL** — connections, transactions/sec, cache hit ratio
- **Containers (cAdvisor)** — per-container CPU, memory, network RX/TX
- **Ad Funnel** — events/sec timeseries, win rate and CTR gauges, cumulative counts
- **Per-Campaign** — bids/sec per campaign, per-campaign win rate

Grid positions were kept consistent — each new section sits at increasing
y offsets below existing panels, no overlap.

### What's intentionally deferred (Phase 17+)

- **Loki + Promtail (log aggregation)** — changes logback config and
  adds two services; warrants its own phase after we've run tests and
  know which log queries we actually need.
- **OpenTelemetry / Jaeger distributed tracing** — significant code
  instrumentation per stage; ROI is lower for a single-JVM bidder where
  per-stage latency already tells us where time is spent.
- **Prometheus AlertManager** — alert rules without a notification sink
  (Slack, PagerDuty, email) are pointless. Defer until we have a channel.
- **FileDescriptorMetrics binder** — minor; JVM handle count rarely
  matters for a bidder this size.

## Verification performed

- Full `docker-compose up -d` brings up all 10 services (6 original + 4 exporters).
- `curl localhost:9090/api/v1/targets` — all 5 Prometheus scrape jobs report `up`.
- Bidder starts cleanly with `EVENTS_TYPE=kafka`; event-loop lag probe logs confirm it's running.
- `/metrics` exposes all new series: `event_loop_lag_seconds`, `bid_context_pool_*`,
  `redis_client_command_duration_seconds`, `kafka_producer_*`, `wins_total`,
  `impressions_total`, `clicks_total`, `win_rate`, `ctr`,
  `campaign_bids_total`, `campaign_wins_total`, `bid_responses_total{reason=...}`.
- Fired 50 bid requests → `bid_responses_total{reason="matched"}` = 50,
  `campaign_bids_total` breaks down by `camp-003/004/006/007/008/009`,
  `bid_context_pool_available` = 1 (pool parking used contexts).
- Win + impression + click → funnel counters populate, `ctr` and `win_rate`
  compute correctly.
