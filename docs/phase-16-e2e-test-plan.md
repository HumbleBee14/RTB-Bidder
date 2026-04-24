# Phase 16 — End-to-end Observability Test Plan

Tick each box as you verify. Total time: ~20 minutes (excluding k6 load tests).

> **Run from project root:**
> `cd /Users/humblebee/Documents/GitHub/Weekend-Learnings/performance-engineering/level-9-project-rtb-bidder/java-rtb-bidder`

---

## Phase 1 — Infrastructure readiness  *(~1 min)*

Prove every component is up and every Prometheus target is being scraped.

```bash
make setup            # docker-compose up -d + seed redis
make infra-status     # should show 10 containers "running"
```

- [ ] All 10 containers running: `redis`, `redis-exporter`, `kafka`, `kafka-exporter`, `postgres`, `postgres-exporter`, `clickhouse`, `prometheus`, `grafana`, `cadvisor`

```bash
curl -s http://localhost:9090/api/v1/targets | \
  python3 -c "import sys,json; [print(f\"{t['labels']['job']:12} {t['health']}\") for t in json.load(sys.stdin)['data']['activeTargets']]"
```

- [ ] Prometheus shows: `cadvisor up`, `kafka up`, `postgres up`, `redis up`, `rtb-bidder down` (bidder not started yet — expected)

---

## Phase 2 — Bidder smoke test  *(~2 min)*

**Terminal 1** — start the bidder:
```bash
EVENTS_TYPE=kafka make run-jar
```

- [ ] Startup log shows `RTB Bidder started on port 8080`
- [ ] Log shows `Event-loop lag probe started (interval=100ms)`
- [ ] Log shows `KafkaEventPublisher connected: localhost:9092 | topics: bid-events, win-events, impression-events, click-events`
- [ ] Log shows `Connected to Redis: localhost:6379`
- [ ] No `ERROR` lines during startup

**Terminal 2** — confirm metrics exposed:
```bash
make health
```
- [ ] Returns JSON with `"status":"UP"` (and `kafka`, `redis` all UP)

```bash
curl -s http://localhost:8080/metrics | grep -Ec "^(event_loop_lag|bid_context_pool|redis_client_command|kafka_producer|wins_total|impressions_total|clicks_total|win_rate|ctr|campaign_bids|campaign_wins|win_unknown_campaign|bid_responses_total.*reason)"
```
- [ ] Count is ≥ 50 (one per percentile / tag combination)

```bash
curl -s http://localhost:9090/api/v1/targets | python3 -c "import sys,json; print([t['health'] for t in json.load(sys.stdin)['data']['activeTargets'] if t['labels']['job']=='rtb-bidder'])"
```
- [ ] Now shows `['up']` for the bidder target

---

## Phase 3 — Happy-path traffic  *(~2 min)*

Fire 100 bids across random users:

```bash
for i in $(seq 1 100); do
  user=$(printf "user_%05d" $((RANDOM % 10000 + 1)))
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"$user\",\"app\":{\"id\":\"a1\",\"category\":\"sports\",\"bundle\":\"com.sports\"},\"device\":{\"type\":\"mobile\",\"os\":\"android\",\"geo\":\"US\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.10}]}" > /dev/null &
done
wait
```

Fire full funnel — one win/impression/click:
```bash
curl -s -X POST http://localhost:8080/win -H "Content-Type: application/json" \
  -d '{"bid_id":"test1","campaign_id":"camp-004","clearing_price":1.5}' > /dev/null
curl -s "http://localhost:8080/impression?bid_id=test1&user_id=user_00001&campaign_id=camp-004&slot_id=s1" > /dev/null
curl -s "http://localhost:8080/click?bid_id=test1&user_id=user_00001&campaign_id=camp-004&slot_id=s1" > /dev/null
```

Verify funnel:
```bash
curl -s http://localhost:8080/metrics | grep -E "^(wins_total|impressions_total|clicks_total|win_rate|ctr|bid_fill_rate) "
```

- [ ] `bid_fill_rate > 0` (some bids matched)
- [ ] `wins_total 1.0`
- [ ] `impressions_total 1.0`
- [ ] `clicks_total 1.0`
- [ ] `ctr 1.0` (1 click / 1 impression)
- [ ] `win_rate` ≈ 1/(number of winning bids)

Per-campaign distribution:
```bash
curl -s http://localhost:8080/metrics | grep "^campaign_bids_total"
```
- [ ] Multiple `camp-XXX` entries visible

---

## Phase 4 — Adversarial / fault injection  *(~5 min)*

**This is the important part.** Deliberately break things and prove each observability signal fires.

### 4a — Cardinality attack on /win  *(~30 sec)*

```bash
for id in attack-1 attack-2 attack-3 "$(head -c 500 /dev/urandom | base64 | tr -d '\n' | head -c 400)"; do
  curl -s -X POST http://localhost:8080/win -H "Content-Type: application/json" \
    -d "{\"bid_id\":\"x\",\"campaign_id\":\"$id\",\"clearing_price\":1}" > /dev/null
done
```

- [ ] Counter increments:
  ```bash
  curl -s http://localhost:8080/metrics | grep "^win_unknown_campaign_total"
  ```
  Expected: `4.0`
- [ ] **No** per-campaign series created for attack IDs:
  ```bash
  curl -s http://localhost:8080/metrics | grep "campaign_wins_total" | grep -iE "attack|==" || echo "confirmed none"
  ```
  Expected output: `confirmed none`
- [ ] No WARN log lines for the attack IDs (they should be INFO-level with truncated IDs):
  ```bash
  grep -E "WARN.*unknown campaignId" logs/rtb-bidder.log && echo "FAIL — WARN should not fire" || echo "PASS — no WARN"
  ```

### 4b — Kill Redis → circuit breaker opens  *(~1 min)*

```bash
docker-compose pause redis
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d '{"user_id":"user_00001","app":{"id":"a1","category":"sports","bundle":"com.s"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"s","sizes":["300x250"],"bid_floor":0.1}]}' > /dev/null
done
sleep 2
```

- [ ] Circuit breaker flipped to OPEN:
  ```bash
  curl -s http://localhost:8080/metrics | grep '^circuit_breaker_state{name="redis"'
  ```
  Expected: value `1.0` (OPEN)
- [ ] `bid_responses_total{reason="NO_MATCHING_CAMPAIGN"}` climbs (Redis returns empty segments → no campaigns match):
  ```bash
  curl -s http://localhost:8080/metrics | grep "NO_MATCHING_CAMPAIGN"
  ```

Recover:
```bash
docker-compose unpause redis
sleep 10
```
- [ ] Circuit breaker returns to CLOSED (value `0.0`):
  ```bash
  curl -s http://localhost:8080/metrics | grep '^circuit_breaker_state{name="redis"'
  ```

### 4c — Saturate event loop → lag probe spikes  *(~1 min)*

Note idle baseline first:
```bash
curl -s http://localhost:8080/metrics | grep "^event_loop_lag_seconds{quantile=\"0.99\"}"
```
- [ ] Idle p99 < 2 ms

Fire 2000 concurrent requests:
```bash
for i in $(seq 1 2000); do
  user=$(printf "user_%05d" $((RANDOM % 10000 + 1)))
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"$user\",\"app\":{\"id\":\"a\",\"category\":\"s\",\"bundle\":\"b\"},\"device\":{\"type\":\"m\",\"os\":\"a\",\"geo\":\"U\"},\"ad_slots\":[{\"id\":\"s\",\"sizes\":[\"300x250\"],\"bid_floor\":0.1}]}" > /dev/null &
done
wait

curl -s http://localhost:8080/metrics | grep "^event_loop_lag_seconds{"
```

- [ ] p99 / p999 climbed noticeably above the idle baseline (may still be small on fast hardware; the signal should visibly move)

### 4d — Pool saturation — zero-alloc proof  *(~2 min)*

Capture baseline:
```bash
BASELINE=$(curl -s http://localhost:8080/metrics | grep "^bid_context_pool_total_created" | awk '{print $2}')
echo "Baseline total_created: $BASELINE"
```

Fire another 1000 requests:
```bash
for i in $(seq 1 1000); do
  user=$(printf "user_%05d" $((RANDOM % 10000 + 1)))
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"$user\",\"app\":{\"id\":\"a\",\"category\":\"s\",\"bundle\":\"b\"},\"device\":{\"type\":\"m\",\"os\":\"a\",\"geo\":\"U\"},\"ad_slots\":[{\"id\":\"s\",\"sizes\":[\"300x250\"],\"bid_floor\":0.1}]}" > /dev/null &
done
wait

AFTER=$(curl -s http://localhost:8080/metrics | grep "^bid_context_pool_total_created" | awk '{print $2}')
echo "After: $AFTER"
echo "Delta: $(python3 -c "print(float('$AFTER') - float('$BASELINE'))")"
```

- [ ] Delta is small (near zero or tiny double-digit). If it climbs by 1000, pool is undersized — zero-alloc broken.

---

## Phase 5 — k6 load tests + live Grafana  *(~10 min)*

Open Grafana fullscreen in a browser tab first: **http://localhost:3000** (admin/admin).

Run each test in a terminal while watching the dashboard:

```bash
make load-test-baseline    # 100 RPS for 2 min — establishes baseline percentiles
```
- [ ] Grafana QPS panel tracks 100 RPS
- [ ] Latency p99 stays under 50 ms
- [ ] Event loop lag stays under a few ms
- [ ] Fill rate gauge is stable

```bash
make load-test-ramp        # 50 → 1000 RPS over ~4 min — find the knee
```
- [ ] QPS climbs in steps as expected
- [ ] Somewhere on the curve, p99 starts rising sharply — that's the saturation point
- [ ] Event loop lag starts creeping up near saturation
- [ ] CPU utilisation climbs to match load

```bash
make load-test-spike       # sudden 10x burst — recovery time test
```
- [ ] QPS jumps instantly
- [ ] p99 spikes briefly then recovers
- [ ] No circuit breakers trip (unless deliberately testing it)

---

## Phase 6 — Visual panel audit  *(~5 min, manual)*

Open Grafana, scroll top to bottom with traffic still flowing. Tick each panel that renders correctly with data:

### Original panels (existing functionality)
- [ ] Bid QPS
- [ ] Bid vs No-Bid Rate
- [ ] Fill Rate (gauge)
- [ ] Latency Percentiles
- [ ] Error & Timeout Rate
- [ ] Pipeline Stage Latency
- [ ] Frequency Cap & Budget Hits
- [ ] Circuit Breaker State
- [ ] JVM Memory (Heap)
- [ ] GC Pause Time
- [ ] GC Pauses/min

### NEW — Phase 16 panels
- [ ] **No-Bid Reasons (per sec)** — stacked area by `reason` label
- [ ] **Event Loop Lag (ms)** — 4 percentile lines
- [ ] **BidContext Pool** — 2 lines: available + total_created
- [ ] **CPU Utilization** — process + system
- [ ] **JVM Threads** — live / daemon / peak

### NEW — Redis section
- [ ] Redis — Client-side Command Latency (per-command percentiles)
- [ ] Redis — Memory
- [ ] Redis — Connected Clients
- [ ] Redis — Ops/sec by command
- [ ] Redis — Keyspace Hits vs Misses
- [ ] Redis — Evictions & Expirations

### NEW — Kafka section
- [ ] Kafka — Client Send Rate
- [ ] Kafka — Client Retry / Error Rate
- [ ] Kafka — Messages In/sec per topic
- [ ] Kafka — Consumer Group Lag
- [ ] Kafka — Log End Offset per topic
- [ ] Kafka — Under-replicated Partitions (should be 0 / green)

### NEW — PostgreSQL section *(populates only if `CAMPAIGNS_SOURCE=postgres`)*
- [ ] PG — Connections
- [ ] PG — Transactions/sec
- [ ] PG — Cache Hit Ratio (gauge)

### NEW — Containers section (cAdvisor)
- [ ] Per-Container CPU (one line per service)
- [ ] Per-Container Memory (RSS)
- [ ] Per-Container Network RX
- [ ] Per-Container Network TX

### NEW — Ad Funnel section
- [ ] Funnel Events/sec (4 lines)
- [ ] Win Rate (gauge)
- [ ] CTR (gauge)
- [ ] Cumulative Funnel Counts (stat panel)

### NEW — Per-Campaign section
- [ ] Per-Campaign Bids/sec (one line per campaign_id)
- [ ] Per-Campaign Win Rate (one line per campaign_id)

---

## Cleanup

```bash
# Stop the bidder (Ctrl+C in Terminal 1, or from another terminal):
pkill -f rtb-bidder-1.0.0.jar

# Stop Docker (data preserved for next run):
make infra-stop
```

---

## When to re-run this plan

- Before merging any PR that touches observability (metrics, dashboards, exporters)
- Before any major load test — makes sure the signals you'll be watching actually work
- After upgrading any exporter image or Micrometer version

## Follow-up work

The Phase 1-4 checks could be scripted into a single `make verify-observability` target for use as a regression gate. Left as a follow-up so this plan stays human-readable and teaches what each signal means.
