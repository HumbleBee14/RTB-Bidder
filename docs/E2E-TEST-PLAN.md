# RTB Bidder — End-to-End Test Plan

A tickable manual checklist covering **every feature, service, endpoint, and running mode** of the bidder. Walk through top to bottom; each section assumes the previous one passed.

> **Run from project root:**
> `cd /Users/humblebee/Documents/GitHub/Weekend-Learnings/performance-engineering/level-9-project-rtb-bidder/java-rtb-bidder`

> **Reference docs:** [GUIDE.md](GUIDE.md) (operations manual) · [ARCHITECTURE.md](ARCHITECTURE.md) (component deep-dive) · [PLAN.md](PLAN.md) (phase roadmap)

---

## Table of contents

- [Part A — Setup & infrastructure](#part-a--setup--infrastructure)
- [Part B — HTTP endpoints](#part-b--http-endpoints)
- [Part C — Pipeline (8 stages)](#part-c--pipeline-8-stages)
- [Part D — Configurable modes](#part-d--configurable-modes)
- [Part E — Resilience (circuit breakers + graceful degradation)](#part-e--resilience)
- [Part F — Full event lifecycle (bid → win → impression → click)](#part-f--full-event-lifecycle)
- [Part G — Observability](#part-g--observability)
- [Part H — Load testing (k6)](#part-h--load-testing)
- [Part I — Analytics (ClickHouse)](#part-i--analytics)
- [Part J — Cleanup](#part-j--cleanup)

---

# Part A — Setup & infrastructure

## A.1  Infrastructure — all 10 services up

```bash
make setup          # docker-compose up -d + seed Redis (waits for Redis ready, then seeds)
make infra-status
```

- [ ] All 10 containers running:
      `redis`, `redis-exporter`, `kafka`, `kafka-exporter`, `postgres`, `postgres-exporter`, `clickhouse`, `prometheus`, `grafana`, `cadvisor`

## A.2  Prometheus is scraping every target

```bash
curl -s http://localhost:9090/api/v1/targets | \
  python3 -c "import sys,json; [print(f\"{t['labels']['job']:12} {t['health']}\") for t in json.load(sys.stdin)['data']['activeTargets']]"
```

- [ ] `cadvisor up` · `kafka up` · `postgres up` · `redis up`
- [ ] `rtb-bidder down` (bidder not started yet — expected)

## A.3  Build the bidder

```bash
make build
ls -lh target/rtb-bidder-1.0.0.jar
```

- [ ] Maven build succeeds (exit 0)
- [ ] Fat JAR exists, ~130 MB

## A.4  Bidder starts cleanly

**Terminal 1** — start bidder with Kafka events:
```bash
EVENTS_TYPE=kafka make run-jar
```

- [ ] Log shows `Connected to Redis: localhost:6379`
- [ ] Log shows `Loaded 10 campaigns from campaigns.json`
- [ ] Log shows `Circuit breakers: redis(failures=5, cooldown=10000ms), kafka(failures=5, cooldown=30000ms)`
- [ ] Log shows `Event-loop lag probe started (interval=100ms)`
- [ ] Log shows `KafkaEventPublisher connected: localhost:9092`
- [ ] Log shows `RTB Bidder started on port 8080 | SLA: 50ms | stages: 8`
- [ ] Zero `ERROR` lines during startup

---

# Part B — HTTP endpoints

## B.1  `GET /health`

```bash
curl -s http://localhost:8080/health | jq .
```
- [ ] HTTP 200 with `"status":"UP"` and component-level UP for `redis`, `kafka`

## B.2  `GET /metrics`

```bash
curl -s http://localhost:8080/metrics | head -5
curl -s http://localhost:8080/metrics | grep -c "^bid_"
```
- [ ] Prometheus text format (`# HELP ...`, `# TYPE ...` headers)
- [ ] At least 10 `bid_*` metrics exposed on a cold (no-traffic) bidder

> **Why not 20?** Most counters and timer percentiles are lazy — they only appear in `/metrics` after the first increment. On a fresh start with zero traffic, `grep -c "^bid_"` returns ~14 (pre-registered counters + static lines). After you fire a bid in B.4 the count jumps to 25+. Run this check again after B.4 if you want to verify the full set.

## B.3  `GET /docs` (OpenAPI spec)

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/docs
```
- [ ] HTTP 200 — OpenAPI YAML served

## B.4  `POST /bid` — valid request

```bash
curl -s -X POST http://localhost:8080/bid \
  -H "Content-Type: application/json" \
  -d '{"user_id":"user_00001","app":{"id":"a1","category":"sports","bundle":"com.sports"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"s1","sizes":["300x250"],"bid_floor":0.1}]}' -i | head -10
```
- [ ] HTTP 200 + JSON body with `bids` array (user_00001 has `fitness` → Nike/HealthPlus bid)
- [ ] Response has `bid_id`, `ad_id`, `price`, `creative_url`, `tracking_urls`

## B.5  `POST /bid` — invalid requests (return 204 with reason)

```bash
# Missing user_id
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d '{"ad_slots":[{"id":"s","sizes":["300x250"],"bid_floor":0.1}]}' -i | head -5

# No ad_slots
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d '{"user_id":"user_00001","ad_slots":[]}' -i | head -5

# User with no Redis segments (unknown user)
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d '{"user_id":"ghost_user_xyz","app":{"id":"a","category":"s","bundle":"b"},"device":{"type":"m","os":"a","geo":"U"},"ad_slots":[{"id":"s","sizes":["300x250"],"bid_floor":0.1}]}' -i | head -5
```
- [ ] Each returns HTTP 204 with `X-NoBid-Reason` header (`NO_MATCHING_CAMPAIGN` etc.)

## B.6  `POST /win`

```bash
curl -s -X POST http://localhost:8080/win -H "Content-Type: application/json" \
  -d '{"bid_id":"test1","campaign_id":"camp-001","clearing_price":0.75}' -i | head -5
```
- [ ] HTTP 200 with `{"status":"acknowledged"}`

## B.7  `GET /impression`

```bash
curl -s -o /dev/null -w "%{http_code} type=%{content_type}\n" \
  "http://localhost:8080/impression?bid_id=test1&user_id=user_00001&campaign_id=camp-001&slot_id=s1"
```
- [ ] HTTP 200, `content_type=image/gif` (1x1 tracking pixel)

## B.8  `GET /click`

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:8080/click?bid_id=test1&user_id=user_00001&campaign_id=camp-001&slot_id=s1"
```
- [ ] HTTP 200

## B.9  Input validation — malicious tracking params

```bash
# Oversized bid_id → rejected
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:8080/impression?bid_id=$(head -c 200 /dev/urandom | base64)&user_id=u&campaign_id=c&slot_id=s"

# Control chars in user_id → rejected
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:8080/impression?bid_id=b&user_id=a%0D%0Ainjected&campaign_id=c&slot_id=s"
```
- [ ] Both return HTTP 400

---

# Part C — Pipeline (8 stages)

## C.1  Fire traffic and check per-stage latency metric

```bash
for i in $(seq 1 50); do
  user=$(printf "user_%05d" $((RANDOM % 10000 + 1)))
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"$user\",\"app\":{\"id\":\"a\",\"category\":\"s\",\"bundle\":\"b\"},\"device\":{\"type\":\"m\",\"os\":\"a\",\"geo\":\"U\"},\"ad_slots\":[{\"id\":\"s\",\"sizes\":[\"300x250\"],\"bid_floor\":0.1}]}" > /dev/null &
done
wait
curl -s http://localhost:8080/metrics | grep "pipeline_stage_latency_seconds_count" | awk -F'stage="' '{print $2}' | cut -d'"' -f1 | sort -u
```
- [ ] Output contains all 8 stage names: `RequestValidation`, `UserEnrichment`, `CandidateRetrieval`, `FrequencyCap`, `Scoring`, `Ranking`, `BudgetPacing`, `ResponseBuild`

## C.2  SLA timeout enforcement

The pipeline has a 50ms deadline. Hard to provoke deliberately without code changes — mark this as a passive check: under normal load `bid_responses_total{outcome="timeout"}` should stay at 0.
```bash
curl -s http://localhost:8080/metrics | grep 'bid_responses_total{outcome="timeout"'
```
- [ ] Value is 0 under normal load (or only non-zero under extreme saturation in Part H)

## C.3  Zero-allocation hot path (Phase 11)

```bash
BEFORE=$(curl -s http://localhost:8080/metrics | grep "^bid_context_pool_total_created" | awk '{print $2}')
# Fire 1000 more bids
for i in $(seq 1 1000); do
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d '{"user_id":"user_00001","app":{"id":"a","category":"s","bundle":"b"},"device":{"type":"m","os":"a","geo":"U"},"ad_slots":[{"id":"s","sizes":["300x250"],"bid_floor":0.1}]}' > /dev/null &
done
wait
AFTER=$(curl -s http://localhost:8080/metrics | grep "^bid_context_pool_total_created" | awk '{print $2}')
python3 -c "print(f'delta: {float(\"$AFTER\") - float(\"$BEFORE\")}')"
```
- [ ] Delta is small (near 0 or < ~20). A delta of 1000 means pool is undersized → Phase 11 regression.

---

# Part D — Configurable modes

Each of these requires a bidder restart with different env vars. Stop bidder in Terminal 1 (`Ctrl+C`) between tests.

## D.1  Targeting strategies

### D.1.a  `segment` (default)
```bash
TARGETING_TYPE=segment make run-jar &
sleep 5; make bid; pkill -f rtb-bidder
```
- [ ] Response has bids (user has `fitness`, Nike targets `fitness`)

### D.1.b  `embedding`
```bash
TARGETING_TYPE=embedding make run-jar &
sleep 5; make bid; pkill -f rtb-bidder
```
- [ ] Startup log shows `Using embedding targeting`
- [ ] Bid request returns 200 or 204 (depends on embedding similarity)

### D.1.c  `hybrid`
```bash
TARGETING_TYPE=hybrid make run-jar &
sleep 5; make bid; pkill -f rtb-bidder
```
- [ ] Startup log shows `Using hybrid targeting`
- [ ] Bids populate as expected

## D.2  Scoring strategies

### D.2.a  `feature-weighted` (default)
```bash
SCORING_TYPE=feature-weighted make run-jar &
sleep 5; curl -s http://localhost:8080/metrics | grep "pipeline_stage_latency_seconds_count" | grep Scoring; pkill -f rtb-bidder
```
- [ ] Scorer initialised as `FeatureWeightedScorer`

### D.2.b  `ml`
```bash
SCORING_TYPE=ml make run-jar &
sleep 5; make bid; pkill -f rtb-bidder
```
- [ ] Log shows ONNX model loaded; bid response has reasonable price

### D.2.c  `abtest`
```bash
SCORING_TYPE=abtest make run-jar &
sleep 5; make bid; pkill -f rtb-bidder
```
- [ ] Log shows A/B scorer initialised

### D.2.d  `cascade`
```bash
SCORING_TYPE=cascade make run-jar &
sleep 5; make bid; pkill -f rtb-bidder
```
- [ ] Log shows cascade scorer

## D.3  Budget pacing strategies

### D.3.a  `local` (default — AtomicLong)
- [ ] Log shows `Using local budget pacer (AtomicLong)`
- [ ] `budget_exhausted_total` starts at 0; climbs if you drain a campaign

### D.3.b  `distributed` (Redis DECRBY)
```bash
PACING_TYPE=distributed make run-jar &
sleep 5; pkill -f rtb-bidder
```
- [ ] Log confirms distributed pacer

### D.3.c  Hourly pacing wrapper
```bash
PACING_HOURLY_ENABLED=true make run-jar &
sleep 5; pkill -f rtb-bidder
```
- [ ] Log shows hourly pacer wrapping base pacer

### D.3.d  Quality-throttled pacing (Phase 15)
```bash
PACING_QUALITY_THROTTLING_ENABLED=true SCORING_TYPE=ml make run-jar &
sleep 5; pkill -f rtb-bidder
```
- [ ] Log shows quality throttling active

## D.4  Campaign source

### D.4.a  `json` (default)
- [ ] Log shows `Loaded 10 campaigns from campaigns.json`

### D.4.b  `postgres`
```bash
CAMPAIGNS_SOURCE=postgres make run-jar &
sleep 5
# Confirm campaigns loaded from Postgres
docker exec $(docker-compose ps -q postgres) psql -U rtb -d rtb -c "SELECT COUNT(*) FROM campaigns;"
pkill -f rtb-bidder
```
- [ ] Log shows `Using PostgreSQL campaign repository`
- [ ] Postgres shows 10 rows in `campaigns` table

## D.5  Events

### D.5.a  `noop` (default — events logged at DEBUG)
- [ ] Log shows `Event publisher: noop`

### D.5.b  `kafka`
```bash
EVENTS_TYPE=kafka make run-jar &
sleep 5
make bid
docker exec -i $(docker-compose ps -q kafka) /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 --topic bid-events --from-beginning --timeout-ms 3000 2>&1 | tail -3
pkill -f rtb-bidder
```
- [ ] Kafka consumer sees a JSON bid event

---

# Part E — Resilience

Restart bidder: `EVENTS_TYPE=kafka make run-jar`

## E.1  Redis outage

```bash
docker-compose pause redis
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d '{"user_id":"user_00001","app":{"id":"a","category":"s","bundle":"b"},"device":{"type":"m","os":"a","geo":"U"},"ad_slots":[{"id":"s","sizes":["300x250"],"bid_floor":0.1}]}' > /dev/null
done
sleep 2
curl -s http://localhost:8080/metrics | grep 'circuit_breaker_state{name="redis"'
```
- [ ] Circuit breaker state = `1.0` (OPEN) after 5+ failed Redis calls
- [ ] Bidder continues responding (HTTP 204, does not crash)

```bash
docker-compose unpause redis
sleep 15
curl -s http://localhost:8080/metrics | grep 'circuit_breaker_state{name="redis"'
```
- [ ] After cooldown, breaker returns to CLOSED (`0.0`)

## E.2  Kafka outage

```bash
docker-compose pause kafka
for i in $(seq 1 20); do make bid > /dev/null; done
sleep 2
curl -s http://localhost:8080/metrics | grep 'circuit_breaker_state{name="kafka'
```
- [ ] Kafka circuit breaker OPEN
- [ ] Bid responses still successful (events are fire-and-forget)

```bash
docker-compose unpause kafka
sleep 35
```
- [ ] Kafka circuit eventually returns to CLOSED

## E.3  Cardinality attack on `/win` (Phase 16 hardening)

```bash
for id in attack-1 attack-2 attack-3 "$(head -c 400 /dev/urandom | base64 | tr -d '\n' | head -c 300)"; do
  curl -s -X POST http://localhost:8080/win -H "Content-Type: application/json" \
    -d "{\"bid_id\":\"x\",\"campaign_id\":\"$id\",\"clearing_price\":1}" > /dev/null
done
```
- [ ] Counter increments to `4.0`:
      `curl -s http://localhost:8080/metrics | grep "^win_unknown_campaign_total"`
- [ ] No new `campaign_wins_total{campaign_id=…attack…}` series created:
      `curl -s http://localhost:8080/metrics | grep "campaign_wins_total" | grep -i attack || echo "confirmed"`
- [ ] No WARN lines in log for those IDs (DEBUG only):
      `grep -E "WARN.*unknown campaignId" logs/rtb-bidder.log || echo "pass"`

---

# Part F — Full event lifecycle

```bash
# 1) Bid
BID=$(curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d '{"user_id":"user_00001","app":{"id":"a","category":"s","bundle":"b"},"device":{"type":"m","os":"a","geo":"U"},"ad_slots":[{"id":"s","sizes":["300x250"],"bid_floor":0.1}]}')
echo "$BID" | jq .
BID_ID=$(echo "$BID" | jq -r '.bids[0].bid_id')

# 2) Win
curl -s -X POST http://localhost:8080/win -H "Content-Type: application/json" \
  -d "{\"bid_id\":\"$BID_ID\",\"campaign_id\":\"camp-001\",\"clearing_price\":0.75}"

# 3) Impression
curl -s "http://localhost:8080/impression?bid_id=$BID_ID&user_id=user_00001&campaign_id=camp-001&slot_id=s" -o /dev/null

# 4) Click
curl -s "http://localhost:8080/click?bid_id=$BID_ID&user_id=user_00001&campaign_id=camp-001&slot_id=s" -o /dev/null

sleep 2
curl -s http://localhost:8080/metrics | grep -E "^(wins_total|impressions_total|clicks_total|win_rate|ctr) "
```
- [ ] `wins_total` incremented
- [ ] `impressions_total` incremented
- [ ] `clicks_total` incremented
- [ ] `win_rate` > 0
- [ ] `ctr = 1.0` (1 click / 1 impression)

Verify events reached Kafka:
```bash
for topic in bid-events win-events impression-events click-events; do
  echo "=== $topic ==="
  docker exec -i $(docker-compose ps -q kafka) /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:29092 --topic "$topic" --from-beginning --max-messages 2 --timeout-ms 3000 2>&1 | grep -v Processed
done
```
- [ ] Each topic shows at least 1 JSON event

---

# Part G — Observability

## G.1  Every new Phase 16 metric is exposed

```bash
curl -s http://localhost:8080/metrics | grep -Ec "^(event_loop_lag|bid_context_pool|redis_client_command|kafka_producer|wins_total|impressions_total|clicks_total|win_rate|ctr|campaign_bids|campaign_wins|win_unknown_campaign|bid_responses_total.*reason)"
```
- [ ] Count ≥ 50

## G.2  Every exporter healthy (Prometheus targets page)

Open http://localhost:9090/targets — all 5 jobs should show `UP`:
- [ ] `rtb-bidder`
- [ ] `redis`
- [ ] `kafka`
- [ ] `postgres`
- [ ] `cadvisor`

## G.3  Event-loop lag probe

```bash
curl -s http://localhost:8080/metrics | grep "^event_loop_lag_seconds{"
```
- [ ] p50 / p99 / p999 all populate (p99 < 2ms on idle machine)

## G.4  Grafana dashboards — panel audit

Open **http://localhost:3000** (admin/admin). Scroll top to bottom with traffic flowing.

### Base panels
- [ ] Bid QPS · Bid vs No-Bid Rate · Fill Rate
- [ ] Latency Percentiles · Error & Timeout Rate
- [ ] Pipeline Stage Latency · Frequency Cap & Budget Hits
- [ ] Circuit Breaker State
- [ ] JVM Memory · GC Pause Time · GC Pauses/min

### Phase 16 additions (core bidder)
- [ ] No-Bid Reasons (stacked)
- [ ] Event Loop Lag
- [ ] BidContext Pool
- [ ] CPU Utilization
- [ ] JVM Threads

### Redis section
- [ ] Client-side Command Latency
- [ ] Memory · Connected Clients · Ops/sec by command
- [ ] Keyspace Hits vs Misses · Evictions & Expirations

### Kafka section
- [ ] Client Send Rate · Client Retry/Error Rate
- [ ] Messages In/sec · Consumer Group Lag
- [ ] Log End Offset · Under-replicated Partitions (should be 0)

### PostgreSQL section (needs `CAMPAIGNS_SOURCE=postgres`)
- [ ] Connections · Transactions/sec · Cache Hit Ratio

### Containers (cAdvisor)
- [ ] Per-container CPU · Memory · Network RX/TX

### Ad Funnel
- [ ] Funnel Events/sec · Win Rate · CTR · Cumulative Counts

### Per-Campaign
- [ ] Per-Campaign Bids/sec · Per-Campaign Win Rate

---

# Part H — Load testing

Open Grafana fullscreen in a browser. Run each k6 test while watching.

## H.1  Baseline (100 RPS, 2 min)

```bash
make load-test-baseline
```
- [ ] QPS panel stabilises at ~100 RPS
- [ ] p99 latency < 50ms throughout
- [ ] Event-loop lag stays low (sub-ms)
- [ ] `bid_context_pool_total_created` plateaus within first ~10s
- [ ] 0 errors, 0 timeouts
- [ ] Redis ops/sec shows SMEMBERS dominating

## H.2  Ramp (50 → 1000 RPS, ~4 min)

```bash
make load-test-ramp
```
- [ ] QPS steps up visibly in Grafana
- [ ] Identify the **saturation knee** (step where p99 doubles) — record RPS: `_____`
- [ ] Event-loop lag climbs near saturation — probe catches trouble
- [ ] CPU utilisation climbs to match load
- [ ] No circuit breaker trips

## H.3  Spike (burst to 500 RPS, ~1 min)

```bash
make load-test-spike
```
- [ ] QPS jumps instantly
- [ ] p99 spikes briefly then recovers — record recovery time: `_____`
- [ ] No container OOM / crash

## H.4  Analyse results

```bash
ls -lh results/gc.log
tail -20 results/gc.log
```
- [ ] GC log exists, ZGC pauses visible (should all be sub-millisecond)

---

# Part I — Analytics (ClickHouse)

Events published to Kafka are consumed by ClickHouse's Kafka engine + materialized views into MergeTree tables.

```bash
docker exec -i $(docker-compose ps -q clickhouse) clickhouse-client -d rtb -q "SHOW TABLES"
```
- [ ] Lists: `bid_events`, `win_events`, `impression_events`, `click_events` (+ Kafka engines + MVs)

```bash
docker exec -i $(docker-compose ps -q clickhouse) clickhouse-client -d rtb -q "SELECT COUNT(*) FROM bid_events"
```
- [ ] Count > 0 after running Parts F and H

```bash
docker exec -i $(docker-compose ps -q clickhouse) clickhouse-client -d rtb -q "
SELECT
  toStartOfMinute(timestamp) AS minute,
  COUNT(*) AS events,
  SUM(was_bid) AS bids,
  ROUND(SUM(was_bid) / COUNT(*), 3) AS fill_rate
FROM bid_events
GROUP BY minute ORDER BY minute DESC LIMIT 5"
```
- [ ] Returns per-minute fill rate breakdown

---

# Part J — Cleanup

```bash
# Stop bidder:
pkill -f rtb-bidder-1.0.0.jar

# Stop Docker (data preserved — fast restart next time):
make infra-stop

# OR full wipe (destroys all Redis/Postgres/ClickHouse/Kafka data):
# make infra-reset
```
- [ ] Bidder process gone
- [ ] `docker-compose ps` shows all containers stopped

---

## Follow-up work (explicitly out of scope)

This plan is the **manual canary** — the thing you walk before every major release. Two follow-ups will build on it:

1. **Automation** — `make verify` (and narrower `make verify-observability`) that scripts Parts A through G into a single command, exits non-zero on failure. Turns the manual walkthrough into a regression gate.

2. **Real tests** — JUnit unit tests + Testcontainers integration tests for:
   - BidMetrics (cardinality, counters, gauges)
   - EventLoopLagProbe (probe fires, records drift)
   - WinHandler (cardinality attack rejected, known campaign recorded)
   - BidRequestHandler (funnel wiring, per-campaign attribution)
   - Each targeting / scoring / pacing implementation
   - Full pipeline with mocked Redis/Kafka

Both are follow-up phases after this plan is proven stable by hand.
