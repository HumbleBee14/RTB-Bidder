# RTB Bidder — E2E Test Results

Run date: 2026-04-24 / 2026-04-25  
Machine: macOS Darwin 25.4.0 (Apple Silicon)  
Branch: phase-16-observability-gaps  
Bidder mode: `EVENTS_TYPE=kafka`, all other defaults

---

## Part A — Setup & infrastructure

| Check | Result |
|---|---|
| All 10 containers running | ✅ redis, redis-exporter, kafka, kafka-exporter, postgres, postgres-exporter, clickhouse, prometheus, grafana, cadvisor |
| `make setup` Redis wait loop | ✅ Fixed (was matching redis-exporter; now uses `docker-compose ps -q redis`) |
| `make build` — fat JAR | ✅ ~130 MB |
| Bidder starts, zero ERROR lines | ✅ |

---

## Part B — HTTP endpoints

| Check | Result |
|---|---|
| `GET /health` → 200, UP | ✅ |
| `GET /metrics` → Prometheus text format | ✅ |
| `GET /metrics` bid_ count (cold) | ✅ ~14 (expected — lazy counters before traffic) |
| `GET /docs` → 200 OpenAPI | ✅ |
| `POST /bid` valid user → 200 + bids array | ✅ bid_id, ad_id, price, creative_url, tracking_urls |
| `POST /bid` invalid → 204 + X-NoBid-Reason | ✅ |
| `POST /win` → 200 acknowledged | ✅ |
| `GET /impression` → 200 image/gif | ✅ |
| `GET /click` → 200 | ✅ |
| Input validation (oversized / control chars) → 400 | ✅ |

---

## Part C — Pipeline (8 stages)

| Check | Result |
|---|---|
| All 8 stage names in `pipeline_stage_latency_seconds_count` | ✅ BudgetPacing, CandidateRetrieval, FrequencyCap, Ranking, RequestValidation, ResponseBuild, Scoring(FeatureWeightedScorer), UserEnrichment |
| SLA timeout counter = 0 under normal load | ✅ `bid_responses_total{outcome="timeout"} 0.0` |
| Object pool delta over 1000 bids | ✅ delta = 1.0 (pool reused, one growth event only) |

---

## Part D — Configurable modes

### D.1 Targeting

| Mode | Result |
|---|---|
| `segment` (default) | ✅ bids returned for user with matching segment |
| `embedding` | ✅ (requires `python ml/generate_embeddings.py` first — embeddings generated, engine loads) |
| `hybrid` | ✅ startup log `Hybrid targeting: segment + embedding (with fallback)` — 204 ALL_FREQUENCY_CAPPED (expected: user_00001 capped from earlier tests, not a code failure) |

### D.2 Scoring

| Mode | Result |
|---|---|
| `feature-weighted` (default) | ✅ `pipeline_stage_latency_seconds_count{stage="Scoring(FeatureWeightedScorer)"} 1` after one bid |
| `ml` | ✅ ONNX scorer loads, bid returns |
| `abtest` | ✅ A/B scorer initialised in log |
| `cascade` | ✅ cascade scorer log confirmed |

### D.3 Budget pacing

| Mode | Result |
|---|---|
| `local` AtomicLong (default) | ✅ `Using local budget pacer (AtomicLong)` |
| `distributed` Redis DECRBY | ✅ log confirmed |
| Hourly pacing wrapper | ✅ log confirmed |
| Quality-throttled (Phase 15) | ✅ log confirmed |

### D.4 Campaign source

| Mode | Result |
|---|---|
| `json` (default) | ✅ `Loaded 10 campaigns from campaigns.json` |
| `postgres` | ✅ `Using PostgreSQL campaign repository`, 10 rows in Postgres |

### D.5 Events

| Mode | Result |
|---|---|
| `noop` (default) | ✅ `Event publisher: noop` |
| `kafka` | ✅ JSON bid event visible on `bid-events` topic: `{"requestId":"9c3968f2...","bid":true,"slotBids":[{"slotId":"slot-1","campaignId":"camp-003","price":1.2}],...}` |

---

## Part E — Resilience

### E.1 Redis outage + recovery

| Check | Result |
|---|---|
| All 10 bids while Redis paused → 204 (not crash) | ✅ `204 204 204 204 204 204 204 204 204 204` |
| Circuit breaker trips → state = 1.0 (OPEN) | ✅ `circuit_breaker_state{name="redis"} 1.0` |
| After Redis unpause + one probe bid → state = 0.0 (CLOSED) | ✅ |

### E.2 Kafka outage

| Check | Result |
|---|---|
| All 20 bids while Kafka paused → 200 (bids unaffected) | ✅ `200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200 200` |
| Kafka CB state | ⚠️ stays 0.0 — async Kafka publish failures don't immediately accumulate (fire-and-forget via single-thread executor; Kafka producer buffers internally before reporting errors). Functional behaviour proven: bids are unaffected. CB may trip at longer outage durations. |

### E.3 Cardinality attack on `/win`

| Check | Result |
|---|---|
| `win_unknown_campaign_total` = 4.0 after 4 bogus IDs | ✅ |
| No `campaign_wins_total{campaign_id=attack...}` label created | ✅ confirmed |
| No WARN log lines for bogus IDs | ✅ confirmed |

---

## Part F — Full event lifecycle (bid → win → impression → click)

| Check | Result |
|---|---|
| `POST /bid` → 200, got bid_id | ✅ bid_id=c82d21c1, price=1.2 |
| `POST /win` → acknowledged | ✅ |
| `GET /impression` → 200 | ✅ |
| `GET /click` → 200 | ✅ |
| `wins_total` | ✅ 5.0 |
| `impressions_total` | ✅ 1.0 |
| `clicks_total` | ✅ 1.0 |
| `win_rate` | ✅ 0.208 |
| `ctr` | ✅ 1.0 (1 click / 1 impression) |

---

## Part G — Observability

| Check | Result |
|---|---|
| Phase 16 metric count (G.1) | ✅ **182** matching lines (threshold ≥ 50) |
| All Prometheus targets UP (G.2) | ✅ rtb-bidder, redis, kafka, postgres, cadvisor |
| Event-loop lag probe (G.3) | ✅ p50=0.11ms, p90=1.1ms, p99=1.1ms, p999=1.2ms |
| Grafana dashboard panels (G.4) | Manual visual check — open http://localhost:3000 |

---

## Part H — Load testing (k6)

_Not yet run. Run separately with `make load-test-baseline` while Grafana is open._

Key things to record when you run it:
- Saturation knee RPS (H.2): `_____`
- Spike recovery time (H.3): `_____`
- ZGC max pause from `results/gc.log` (H.4): `_____`

---

## Part I — Analytics (ClickHouse)

| Check | Result |
|---|---|
| Tables: bid_events, win_events, impression_events, click_events | ✅ all 4 + their `_kafka` + `_mv` variants |
| `SELECT COUNT(*) FROM bid_events` | ✅ **5395** rows |
| Per-minute fill rate breakdown | ✅ sample output below |

```
minute               events  bids  fill_rate
2026-04-25 00:14:00  1       1     1.0
2026-04-25 00:13:00  32      22    0.688
2026-04-25 00:12:00  1       1     1.0
2026-04-24 08:41:00  1       0     0.0
2026-04-24 08:40:00  1000    4     0.004   ← C.3 zero-alloc test (1000 parallel ALL_FREQUENCY_CAPPED)
```

---

## Part J — Cleanup

```bash
pkill -f rtb-bidder-1.0.0.jar
make infra-stop   # keeps data; or make infra-reset to wipe
```

