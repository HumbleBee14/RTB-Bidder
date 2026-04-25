# Phase 9: Metrics + Health — Observability

## What was built

Prometheus metrics endpoint with real-time counters, histograms, and gauges. Health check endpoint with per-dependency status. The RED method: Rate, Errors, Duration — the three signals production ad-tech dashboards use.

## Why observability matters for a bidder

At 50K QPS, you can't debug by reading logs. You need:
- **Is the system healthy?** → `/health` (K8s liveness/readiness probe)
- **How fast are we?** → `bid_latency_seconds` histogram (p50/p99/p999)
- **Are we making money?** → `bid_fill_rate` gauge (% of requests we monetize)
- **Which stage is slow?** → `pipeline_stage_latency_seconds` per stage
- **Are campaigns burning out?** → `budget_exhausted_total`, `frequency_cap_hit_total`

## Health Check

```
GET /health
```

```json
{
  "status": "UP",
  "components": {
    "redis": {"status": "UP", "detail": "PONG"},
    "kafka": {"status": "UP", "detail": "1 node(s)"}
  }
}
```

Returns **200** if all dependencies UP, **503** if any DOWN. K8s uses this for liveness/readiness probes — a bidder with dead Redis shouldn't receive traffic.

## Prometheus Metrics

```
GET /metrics → Prometheus text format (scraped every 15s by Prometheus)
```

### Rate metrics
```
bid_requests_total 9.0                      ← total requests (QPS = delta/interval)
bid_responses_total{outcome="bid"} 5.0      ← successful bids
bid_responses_total{outcome="nobid"} 1.0    ← no matching campaign
bid_responses_total{outcome="timeout"} 3.0  ← SLA timeout (cold start)
bid_responses_total{outcome="error"} 0.0    ← internal errors
```

### Duration metrics (latency percentiles)
```
bid_latency_seconds{quantile="0.5"} 0.018   ← p50: 18ms
bid_latency_seconds{quantile="0.9"} 0.130   ← p90
bid_latency_seconds{quantile="0.99"} 0.130  ← p99
bid_latency_seconds{quantile="0.999"} 0.130 ← p999
```

### Per-stage latency (find the bottleneck)
```
pipeline_stage_latency_seconds{stage="RequestValidation"}           0.0001s
pipeline_stage_latency_seconds{stage="UserEnrichment"}              0.07s   ← Redis
pipeline_stage_latency_seconds{stage="FrequencyCap"}                0.24s   ← Redis (slowest)
pipeline_stage_latency_seconds{stage="Scoring(FeatureWeightedScorer)"} 0.0002s
pipeline_stage_latency_seconds{stage="BudgetPacing"}                0.0005s
```

### Business metrics
```
bid_fill_rate 0.5556                    ← 55.6% of requests we monetize
frequency_cap_hit_total 0.0             ← campaigns getting capped
budget_exhausted_total 0.0              ← campaigns running out of budget
```

## Why histogram for latency (not average)

Average latency hides tail latency. If 99% of requests are 10ms but 1% are 500ms, the average is 15ms — looks fine. But that 1% is 500 users per second experiencing timeouts.

Prometheus histograms capture percentile distribution: p50 (median), p99 (99th percentile), p999 (one-in-a-thousand). Production dashboards show p99 — that's the latency that matters for SLA compliance.

## Files

| File | Purpose |
|------|---------|
| `metrics/MetricsRegistry.java` | Micrometer + Prometheus registry singleton |
| `metrics/BidMetrics.java` | Counters, timers, gauges — RED method metrics |
| `health/HealthCheck.java` | Interface + HealthStatus record |
| `health/RedisHealthCheck.java` | Pings Redis |
| `health/KafkaHealthCheck.java` | AdminClient cluster check |
| `health/CompositeHealthCheck.java` | Aggregates all checks, returns JSON |

## How to test

```bash
mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Health check
curl http://localhost:8080/health

# Send some bids
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"s\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30}]}"
done

# Scrape metrics
curl http://localhost:8080/metrics | grep "bid_"
```
