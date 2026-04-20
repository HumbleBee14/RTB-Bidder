# Phase 14: Grafana Dashboard — Visualize Everything

## What was built

Pre-configured Grafana dashboard with 10 panels showing live bidder metrics, auto-provisioned on startup. Prometheus scrapes the bidder's `/metrics` endpoint every 15 seconds. Everything runs in Docker — zero manual setup.

## Why Prometheus + Grafana (and not alternatives)

### Prometheus: metrics collection

**What it does**: Pulls (scrapes) metrics from the bidder's `/metrics` endpoint on a fixed interval. Stores time-series data with labels. Provides PromQL for querying.

**Why pull-based (not push-based like StatsD/Graphite)**:
- Pull means the metrics server controls the scrape rate — no thundering herd from 100 services pushing simultaneously
- If the bidder dies, Prometheus detects it (scrape fails) — with push-based, you can't distinguish "no data" from "service down"
- No client-side buffering or batching needed — the bidder just exposes `/metrics`, Prometheus does the rest

**Why not:**
- **Datadog/New Relic**: SaaS, costs money, vendor lock-in. Prometheus is open-source and runs locally
- **InfluxDB**: Push-based time-series DB. Good for IoT/sensor data. Prometheus is better for infrastructure/app metrics (service discovery, alerting rules, PromQL)
- **OpenTelemetry Collector**: Could replace Prometheus for metrics collection. But Prometheus is simpler for single-service dashboarding and has better Grafana integration

### Grafana: visualization

**What it does**: Connects to Prometheus as a data source. Renders panels using PromQL queries. Auto-refreshes every 10 seconds.

**Why not:**
- **Prometheus built-in UI**: Only supports basic expression browser — no persistent dashboards, no layout, no alerting
- **Kibana**: Designed for Elasticsearch/logs, not Prometheus metrics
- **Custom React dashboard**: Why build what Grafana does perfectly? Time better spent on the bidder itself

## Dashboard panels — what they show and why

### Row 1: Traffic Overview

| Panel | Type | PromQL | Why it matters |
|-------|------|--------|----------------|
| **Bid QPS** | Time series | `rate(bid_requests_total[1m])` | The fundamental throughput metric. If QPS drops, something is wrong upstream (exchange stopped sending) or downstream (bidder is crashing). |
| **Bid vs No-Bid Rate** | Stacked area | `rate(bid_responses_total{outcome="bid"}[1m])` | Shows the breakdown of responses. Stacked area reveals if no-bids are increasing (targeting too narrow? budgets exhausted?) or errors are growing. |
| **Fill Rate** | Gauge | `bid_fill_rate` | THE business metric. "What % of requests do we monetize?" Red below 15%, yellow 15-30%, green above 30%. If fill rate drops, revenue drops. |

### Row 2: Latency & Errors

| Panel | Type | PromQL | Why it matters |
|-------|------|--------|----------------|
| **Latency Percentiles** | Time series | `bid_latency_seconds{quantile="0.99"} * 1000` | p50/p90/p99/p99.9 over time. If p99 climbs past 50ms, you're breaching SLA. Shows if degradation is gradual (load increase) or sudden (GC pause, Redis timeout). |
| **Error & Timeout Rate** | Time series | `rate(bid_responses_total{outcome="error"}[1m])` | Errors in red, timeouts in orange. Any non-zero error rate needs investigation. Timeouts indicate pipeline stages exceeding the SLA deadline. |

### Row 3: Pipeline & Business

| Panel | Type | PromQL | Why it matters |
|-------|------|--------|----------------|
| **Pipeline Stage Latency** | Stacked area | `rate(pipeline_stage_latency_seconds_sum{stage="X"}[1m]) / rate(pipeline_stage_latency_seconds_count{stage="X"}[1m]) * 1000` | Shows where time is spent per stage. In our bidder, FrequencyCap dominates (sync Redis). If a stage suddenly gets slower, this panel pinpoints it instantly. |
| **Frequency Cap & Budget Hits** | Time series | `rate(frequency_cap_hit_total[1m])` | How often are we capping users or exhausting budgets? High cap rate = revenue loss (showing same ads too much). High budget exhaustion = campaigns need more budget. |

### Row 4: Infrastructure

| Panel | Type | PromQL | Why it matters |
|-------|------|--------|----------------|
| **Circuit Breaker State** | Stat | `circuit_breaker_state` | Shows CLOSED (green/0), OPEN (red/1), HALF_OPEN (yellow/2) for each dependency. If Redis circuit opens, targeting degrades to empty segments. |
| **JVM Heap Memory** | Time series | `jvm_memory_used_bytes{area="heap"}` | Heap used vs committed vs max. If used approaches max, you're about to OOM. With ZGC, the gap between used and committed shows GC efficiency. |
| **GC Pause Time** | Time series | `rate(jvm_gc_pause_seconds_sum[1m])` | How much time the JVM spends in GC per second. With ZGC this should be near-zero. A spike means allocation pressure (object pooling isn't working, or a memory leak). |

## How Grafana auto-provisioning works

Grafana supports "provisioning" — config files that auto-configure data sources and dashboards on startup. No manual clicking.

```
docker/grafana/
├── provisioning/
│   ├── datasources/
│   │   └── prometheus.yml     ← Tells Grafana where Prometheus is
│   └── dashboards/
│       └── dashboards.yml     ← Tells Grafana where to find dashboard JSON files
└── dashboards/
    └── rtb-bidder.json        ← The actual dashboard definition (10 panels)
```

On startup, Grafana reads these files and creates the data source and dashboard automatically. The dashboard JSON is the same format you'd get from Grafana's "Export JSON" button — but checked into git so it's version-controlled and reproducible.

## PromQL patterns used

### Rate vs raw counter

```promql
# WRONG — shows cumulative count, always increasing, useless for dashboards
bid_requests_total

# RIGHT — shows requests per second over the last 1 minute
rate(bid_requests_total[1m])
```

`rate()` calculates the per-second average rate of increase. It handles counter resets (service restarts) automatically.

### Computing average latency per stage

```promql
# sum of all durations / count of all calls = average duration per call
rate(pipeline_stage_latency_seconds_sum{stage="FrequencyCap"}[1m])
  / rate(pipeline_stage_latency_seconds_count{stage="FrequencyCap"}[1m])
  * 1000   # convert seconds to milliseconds
```

### Percentiles from summaries

Micrometer publishes pre-computed percentiles as labeled metrics:
```promql
bid_latency_seconds{quantile="0.99"} * 1000   # p99 in milliseconds
```

## Files

| File | Purpose |
|------|---------|
| `docker/prometheus.yml` | Prometheus scrape config — targets `host.docker.internal:8080` |
| `docker/grafana/provisioning/datasources/prometheus.yml` | Auto-configures Prometheus as Grafana data source |
| `docker/grafana/provisioning/dashboards/dashboards.yml` | Tells Grafana where to find dashboard JSON files |
| `docker/grafana/dashboards/rtb-bidder.json` | Pre-configured dashboard with 10 panels |
| `docker-compose.yml` | Added Prometheus and Grafana services |

## How to run

### 1. Start infrastructure

```bash
# Start Prometheus + Grafana (and Redis for the bidder)
docker compose up -d redis prometheus grafana

# If you want Kafka too (for event publishing):
docker compose up -d redis kafka prometheus grafana

docker ps   # verify containers are running
```

### 2. Build and start the bidder

```bash
mvn package -q -DskipTests
java -XX:+UseZGC -Xms256m -Xmx256m -jar target/rtb-bidder-1.0.0.jar
```

### 3. Generate some traffic

```bash
# Seed Redis with users first
bash docker/init-redis.sh | docker exec -i <redis-container> redis-cli --pipe

# Send bid requests
for i in $(seq 1 100); do
  curl -s -o /dev/null -X POST http://localhost:8080/bid \
    -H "Content-Type: application/json" \
    -d "{\"user_id\":\"user_$(printf '%05d' $((RANDOM % 10000 + 1)))\",\"app\":{\"id\":\"a1\",\"category\":\"sports\",\"bundle\":\"com.s\"},\"device\":{\"type\":\"mobile\",\"os\":\"android\",\"geo\":\"US\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.10}]}"
done
```

### 4. Open the dashboard

```
Grafana:    http://localhost:3000  (admin / admin)
Prometheus: http://localhost:9090  (no auth)
```

The RTB Bidder dashboard loads automatically as the home dashboard. Data appears within 15-30 seconds (one Prometheus scrape interval).

### 5. Verify Prometheus is scraping

```bash
# Check target health
curl -s http://localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
# Should show: "health":"up"

# Query a metric
curl -s "http://localhost:9090/api/v1/query?query=bid_requests_total"
```

## What production dashboards look like vs ours

| Feature | Our dashboard | Production (Moloco/AppLovin) |
|---------|--------------|------------------------------|
| Panels | 10 panels, single service | 50+ panels, multi-service (bidder, adserver, ML, infra) |
| Data source | Single Prometheus | Prometheus federation, Thanos for long-term storage |
| Alerting | None (visual only) | PagerDuty/Slack alerts on p99 > SLA, error rate > threshold, fill rate drop |
| Granularity | 15s scrape interval | 1-5s for critical metrics, 15s for capacity planning |
| Multi-instance | Single bidder | Per-instance breakdown, aggregate across cluster |
| Custom metrics | Standard RED + pipeline stages | Per-exchange, per-campaign, per-geo, per-device breakdowns |

Our dashboard covers the same fundamental metrics that production dashboards start with. The difference is scale (more services, more labels, more panels) and alerting (we don't have it yet).
