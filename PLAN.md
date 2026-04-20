# RTB Bidder — Build Plan

---

## Phase 1: Skeleton — HTTP endpoints + OpenRTB-like protocol
**Goal**: Vert.x server running with ALL endpoints a real bidder needs. OpenRTB-like request/response format.

**What you build**:
- `pom.xml` — Maven with Vert.x Web, Jackson, Lettuce, Kafka client, Micrometer
- `Application.java` — entry point, starts Vert.x, wires dependencies
- `server/HttpServer.java` — Vert.x HTTP server on port 8080, Virtual Threads executor
- `server/BidRouter.java` — ALL routes:
  - `POST /bid` — receive bid request, return bid response (the core hot path)
  - `POST /win` — win notification callback from exchange (triggers billing event)
  - `GET /impression` — impression tracking pixel (ad was shown to user)
  - `GET /click` — click tracking (user clicked the ad)
  - `GET /health` — health check for load balancer / K8s
  - `GET /metrics` — Prometheus metrics endpoint
- `server/BidRequestHandler.java` — parse → pipeline → response OR no-bid (HTTP 204)
- `server/WinHandler.java` — receive win notification, publish billing event
- `server/TrackingHandler.java` — handle impression/click pixel, publish tracking event
- `model/BidRequest.java` — OpenRTB-like: user_id, app (id, category, bundle), device (type, os, geo), ad_slots [{id, sizes, bid_floor}]
- `model/BidResponse.java` — OpenRTB-like: bid_id, ad_id, price, creative_url, tracking_urls (impression_url, click_url), advertiser_domain
- `model/NoBidReason.java` — enum: NO_MATCHING_CAMPAIGN, ALL_FREQUENCY_CAPPED, BUDGET_EXHAUSTED, TIMEOUT, INTERNAL_ERROR

**What you learn**: How Vert.x works — event loop, routing, request/response. OpenRTB protocol basics — what fields exist and why. Why a bidder has 6 endpoints, not just 1. Why no-bid (HTTP 204) is a valid and frequent response. How impression/click tracking works (redirect pixel).

**Test**:
```bash
# Bid request
curl -X POST http://localhost:8080/bid -H 'Content-Type: application/json' \
  -d '{"user_id":"u123","app":{"id":"app1","category":"news"},"ad_slots":[{"id":"slot1","sizes":["300x250"],"bid_floor":0.50}]}'

# Should return 200 with bid OR 204 (no-bid)
```

**Commit**: `phase-1: vert.x skeleton — all endpoints, OpenRTB-like protocol, no-bid handling`

---

## Phase 2: Pipeline — BidContext flows through stages with SLA timeout
**Goal**: Pipeline architecture wired. Stages execute in order. Hard SLA timeout — if pipeline exceeds deadline, return no-bid immediately. Still no Redis/Kafka — everything in-memory.

**What you build**:
- `pipeline/PipelineStage.java` — the interface
- `pipeline/BidContext.java` — mutable context: request, user, candidates, winner, response, startTimeNanos, deadlineNanos, noBidReason
- `pipeline/BidPipeline.java` — orchestrator: loops through stages, records per-stage latency, **checks deadline before each stage** — if time remaining < threshold → abort, return no-bid with reason TIMEOUT
- `pipeline/PipelineException.java` — thrown when stage fails (caught by pipeline, returns no-bid)
- `pipeline/stages/RequestValidationStage.java` — validate required fields, check bid_floor present
- `pipeline/stages/ResponseBuildStage.java` — build response from context, include impression_url and click_url tracking URLs
- `config/PipelineConfig.java` — `maxLatencyMs=50` (hard SLA), stage order
- Wire `BidRequestHandler` → `BidPipeline` → response OR no-bid (204)

**What you learn**: Pipeline/chain-of-responsibility pattern. SLA enforcement — why a late response is WORSE than no response (exchange already moved on, your bid is wasted compute). How per-stage latency tracking works. No-bid as a first-class outcome, not an error.

**Test**: Same curl. Response flows through pipeline (logs: "ValidationStage: 0.1ms, ResponseBuildStage: 0.05ms, total: 0.15ms, deadline: 50ms").

**Commit**: `phase-2: pipeline architecture — stages execute in order with latency tracking`

---

## Phase 3: Docker + Redis — Real data store
**Goal**: Redis running in Docker. User segments loaded. UserEnrichmentStage fetches real data.

**What you build**:
- `docker-compose.yml` — Redis (+ PostgreSQL for later)
- `docker/init-redis.sh` — seed script: load 10K users with random segments into Redis Sets
- `config/AppConfig.java`, `config/RedisConfig.java` — configuration
- `repository/UserSegmentRepository.java` — interface
- `repository/RedisUserSegmentRepository.java` — Lettuce async Redis client, `SMEMBERS`
- `pipeline/stages/UserEnrichmentStage.java` — calls repository, attaches segments to BidContext
- `model/UserProfile.java` — user_id + segments

**What you learn**: Lettuce async Redis client. How to structure repository pattern — interface + implementation. How Docker Compose wires services together. Why Redis Sets for segment lookup (O(1) membership check).

**Test**: `curl` with a seeded user_id → response now includes which segments the user belongs to (visible in logs).

**Commit**: `phase-3: redis integration — user segments fetched from Redis via Lettuce`

---

## Phase 4: Campaigns + Targeting — Match users to ads
**Goal**: Campaigns loaded from PostgreSQL (or in-memory for now), cached. Targeting engine matches user segments to campaign rules.

**What you build**:
- `model/Campaign.java` — id, advertiser, budget, bid_floor, targeting_rules (segment list), creative_url
- `model/AdCandidate.java` — campaign + computed score
- `model/AdContext.java` — app category, keywords, device, geo
- `repository/CampaignRepository.java` — interface
- `repository/CachedCampaignRepository.java` — in-memory list of campaigns (hardcoded or loaded from JSON file). Later: wrap PostgresCampaignRepository.
- `targeting/TargetingEngine.java` — interface
- `targeting/SegmentTargetingEngine.java` — match: "campaign targets segment X, user has segment X → candidate"
- `pipeline/stages/CandidateRetrievalStage.java` — calls TargetingEngine, populates context.candidates

**What you learn**: How ad targeting works — campaign has targeting rules (segments, context), engine matches user profile against rules. Decorator pattern for caching. Strategy pattern for targeting (segment-based vs context-based).

**Test**: `curl` → response now returns an actual matched ad (not hardcoded). Different user_ids return different ads based on their segments.

**Commit**: `phase-4: targeting engine — campaigns matched to users based on segments`

---

## Phase 4.5 (stretch): Embedding-Based Targeting — AI-native ad matching
**Goal**: Replace or augment segment matching with semantic similarity. For AI-chat ad networks, the conversation context is the signal — not just user segments. Use embeddings to match ad content to conversation context.

**Why this matters**: Classical ad-tech matches on predefined segments ("sports fan", "age 25-34"). But in AI chat apps, the context is a conversation — free-form text. Embedding similarity lets you match "user is talking about hiking gear" to an outdoor equipment ad without hardcoded segments.

**What you build**:
- `targeting/EmbeddingTargetingEngine.java` — implements `TargetingEngine` interface
  - Takes conversation context (text) from BidRequest
  - Computes embedding vector using a lightweight model (all-MiniLM-L6-v2 via ONNX Runtime or a pre-computed lookup)
  - Compares against pre-computed campaign embeddings (each campaign has an embedding of its ad content/keywords)
  - Returns top-N most similar campaigns as candidates (cosine similarity > threshold)
- `model/BidRequest.java` — add optional `context_text` field (the AI conversation context)
- Campaign embeddings pre-computed at startup and stored in memory (or Redis with `FT.SEARCH` via RediSearch)
- Alternative: use **pgvector** extension in PostgreSQL for similarity search

**What you learn**: How embedding-based retrieval works — the same pattern used in RAG (Retrieval-Augmented Generation). Cosine similarity for matching. Why this is the future of ad targeting in AI-native apps. Trade-off: embedding inference adds 1-5ms per request vs segment lookup at 0.1ms.

**Test**: Send bid request with `context_text: "I'm looking for a good pair of running shoes"` → returns Nike/Adidas campaign (highest cosine similarity), not a random segment match.

**Commit**: `phase-4.5: embedding targeting — semantic match for AI-chat context`

---

## Phase 5: Frequency Capping — Don't annoy users
**Goal**: Filter out campaigns the user has seen too many times.

**What you build**:
- `frequency/FrequencyCapper.java` — interface: `boolean isAllowed(userId, campaignId)`
- `frequency/RedisFrequencyCapper.java` — Redis `INCR user:{uid}:campaign:{cid}` + `EXPIRE 3600` (1 hour window). If count > max_impressions → not allowed.
- `pipeline/stages/FrequencyCapStage.java` — for each candidate, check FrequencyCapper. Remove disallowed.

**What you learn**: How frequency capping works in ad-tech. Redis as a counter with TTL. Why this is per-user per-campaign per-time-window. How one Redis roundtrip per candidate adds up — batch with pipeline if needed.

**Test**: Hit `/bid` 4 times with same user. If campaign has `max_impressions=3`, 4th request returns different ad (or no ad).

**Commit**: `phase-5: frequency capping — Redis INCR+EXPIRE filters over-exposed campaigns`

---

## Phase 6: Scoring + Ranking + Bid Floor — Pick the best ad at the right price
**Goal**: Score each candidate. Enforce exchange bid floor. Rank. Pick the winner.

**What you build**:
- `scoring/Scorer.java` — interface: `double score(Campaign, UserProfile, AdContext)`
- `scoring/FeatureWeightedScorer.java` — `relevance × bid_floor × pacing_factor × recency_boost`
- `pipeline/stages/ScoringStage.java` — score each candidate using Scorer. **Filter out candidates whose computed bid price < exchange's bid_floor** (from BidRequest.ad_slot.bid_floor). No point bidding below the minimum.
- `pipeline/stages/RankingStage.java` — sort by score descending, pick top-1 (or top-K for multi-slot)
- Handle **no candidates remaining** after filtering → set noBidReason = NO_ELIGIBLE_ADS → pipeline returns 204

**What you learn**: How ad scoring works — weighted formula combining relevance, price, and pacing. **Bid floor enforcement** — the exchange sets a minimum price, you must bid above it or don't bid. This is a fundamental RTB concept. Why no-bid is common (40-70% of requests in production).

**Test**: Seed campaign with bid_floor=0.30. Send request with exchange bid_floor=0.50 → no-bid (your campaign can't afford the slot). Send with exchange bid_floor=0.20 → bid returned.

**Commit**: `phase-6: scoring, ranking, bid floor enforcement — strategy pattern with price filtering`

---

## Phase 6.5 (stretch): ML Scoring — pCTR prediction model
**Goal**: Replace the linear formula with a trained ML model that predicts click-through rate (pCTR). This is how production ad-tech scoring actually works — Moloco does 7ms inference at 1M+ QPS.

**Why this matters**: The feature-weighted formula in Phase 6 is interpretable but naive. Real ad-tech uses ML models trained on historical click/impression data to predict: "what's the probability this user clicks this ad?" The bid price = pCTR × value_per_click × pacing_factor.

**What you build**:
- Train a small **XGBoost** model offline (Python script):
  - Features: user_segment (one-hot), app_category, device_type, hour_of_day, campaign_id, bid_floor
  - Label: clicked (0/1) — synthetic data generated from realistic distributions
  - Export to ONNX format
- `scoring/MLScorer.java` — implements `Scorer` interface
  - Load ONNX model via ONNX Runtime Java API
  - Extract features from `UserProfile`, `AdContext`, `Campaign` → feature vector
  - Run inference → pCTR score (0.0 to 1.0)
  - Bid price = pCTR × campaign.value_per_click × pacing_factor
- `scoring/ABTestScorer.java` — routes X% traffic to `FeatureWeightedScorer`, Y% to `MLScorer`
  - Track metrics per variant: CTR, revenue, latency
  - Zero changes to pipeline — both implement `Scorer` interface

**What you learn**: How pCTR prediction works in ad-tech. Feature engineering for ad scoring. ONNX Runtime inference in Java. A/B testing scoring algorithms. Why the Scorer interface makes this a config change, not a code rewrite.

**Test**: Compare FeatureWeightedScorer vs MLScorer on same requests. ML scorer should produce different rankings (more personalized). Benchmark inference latency: should be <5ms per candidate.

**Commit**: `phase-6.5: ML scoring — XGBoost pCTR model via ONNX Runtime`

---

## Phase 7: Budget Pacing — Don't overspend
**Goal**: Each campaign has a budget. Decrement on win. Stop serving when budget exhausted.

**What you build**:
- `pacing/BudgetPacer.java` — interface: `boolean trySpend(campaignId, amount)`
- `pacing/LocalBudgetPacer.java` — `ConcurrentHashMap<String, AtomicLong>`, `trySpend` = `decrementAndGet >= 0`
- `pacing/DistributedBudgetPacer.java` — Redis `DECRBY` for multi-instance (stretch)
- `pipeline/stages/BudgetPacingStage.java` — trySpend on the winner. If fails (budget exhausted), try next candidate.

**What you learn**: How budget pacing works — atomically decrement, fail if exhausted. Why AtomicLong for single-instance, Redis DECRBY for multi-instance. Race condition: two bidders spending the last $1 simultaneously — why atomic operations matter.

**Test**: Set campaign budget to 5. Send 10 requests. First 5 return the ad, last 5 return a different ad (or no-bid).

**Commit**: `phase-7: budget pacing — atomic budget decrement, stops serving when exhausted`

### Phase 7 Future Enhancements (decorator on BudgetPacer — no architecture changes needed)

These wrap the existing `BudgetPacer` interface via decorator pattern. Zero pipeline changes.

| Enhancement | What it does | Why it matters | Implementation |
|-------------|-------------|----------------|----------------|
| **Hourly pacing** | Spreads budget evenly across the day ($1000/day = ~$42/hour) | Without it, morning traffic spike can burn entire budget in 1 hour | ✅ DONE — `HourlyPacedBudgetPacer` decorator |
| **Spend smoothing** | Gradually reduces bid rate as budget depletes (80% spent → bid 50% of requests) | Avoids hard cliff when budget hits zero — smoother campaign delivery | ✅ DONE — built into `HourlyPacedBudgetPacer` (80-95% ramp-down) |
| **Budget monitoring** | Tracks spend/exhaustion/throttle counts per campaign | Operations visibility — catch runaway spend before damage | ✅ DONE — `BudgetMetrics` counters wired into pacers. TODO: Micrometer gauges in Phase 9 |
| **ML-driven throttling** | Adjusts bid rate based on predicted conversion value | Spend more during high-value hours, conserve during low-value | ✅ DONE — `QualityThrottledBudgetPacer` decorator (Phase 15) |

Refs:
- [Optimal Budget Pacing for RTB](http://www0.cs.ucl.ac.uk/staff/w.zhang/rtb-papers/opt-rtb-pacing.pdf)
- [Ad Banker: Budget Allocation](https://clearcode.cc/portfolio/ad-banker-case-study/)
- [RTB Data & Revenue Architecture](https://e-mindset.space/blog/ads-platform-part-3-data-revenue/)

---

## Phase 8: Kafka Events — Full event lifecycle (bid → win → impression → click)
**Goal**: Complete ad event lifecycle. Every bid, win notification, impression, and click publishes to Kafka. Non-blocking. The full billing and attribution pipeline.

**What you build**:
- `event/EventPublisher.java` — interface
- `event/KafkaEventPublisher.java` — async Kafka producer. Fire-and-forget. Batched.
- `event/NoOpEventPublisher.java` — for testing without Kafka
- `event/events/BidEvent.java` — bid_id, campaign_id, user_id, price, timestamp, bid_or_nobid, nobid_reason
- `event/events/WinEvent.java` — bid_id, campaign_id, clearing_price (what you actually pay — may differ from bid in second-price auction)
- `event/events/ImpressionEvent.java` — bid_id, campaign_id, user_id, timestamp (ad was actually SHOWN to user)
- `event/events/ClickEvent.java` — bid_id, campaign_id, user_id, timestamp (user CLICKED the ad)
- `codec/EventCodec.java` — serialize events to JSON for Kafka
- Add Kafka to `docker-compose.yml` with topics: `bid-events`, `win-events`, `impression-events`, `click-events`
- Wire `BidRequestHandler` → publish BidEvent AFTER response sent
- Wire `WinHandler` (`POST /win`) → publish WinEvent (this is when billing happens)
- Wire `TrackingHandler` (`GET /impression`, `GET /click`) → publish ImpressionEvent / ClickEvent

**The ad event lifecycle**:
```
1. Exchange sends bid request      → we respond with bid (or no-bid)     → BidEvent to Kafka
2. Exchange picks winner           → calls our POST /win                 → WinEvent to Kafka (BILLING)
3. User's device loads the ad      → hits GET /impression?bid_id=X       → ImpressionEvent to Kafka
4. User clicks the ad              → hits GET /click?bid_id=X            → ClickEvent to Kafka
```
This is how every ad network works. Without win + impression + click tracking, you can't do billing, attribution, or measure campaign effectiveness.

**What you learn**: Full ad event lifecycle — why you need 4 event types, not just "bid." Why win notification exists (you don't know your bid won until the exchange tells you). Why impression != bid (you bid, but the ad might not render). How click tracking enables CPC (cost-per-click) billing. Kafka topic design for event separation.

**Test**: Send bid → check `bid-events` topic. Call `/win` → check `win-events`. Call `/impression` → check `impression-events`. Full lifecycle visible in Kafka.

**Commit**: `phase-8: full event lifecycle — bid, win, impression, click events to Kafka`

---

## Phase 9: Metrics + Health — Observability
**Goal**: Prometheus metrics endpoint. Health check endpoint. Per-stage latency visible.

**What you build**:
- `metrics/MetricsRegistry.java` — Micrometer registry with Prometheus exporter
- `metrics/BidMetrics.java` — key metrics:
  - `bid_latency_seconds` — histogram (captures p50/p99/p999)
  - `bid_requests_total` — counter (total QPS)
  - `bid_responses_total` — counter, labeled by outcome: `bid`, `nobid`, `error`, `timeout`
  - `bid_fill_rate` — gauge: bids / total requests (THE core business metric — "what % of requests do we monetize?")
  - `bid_errors_total` — counter by error type
  - `pipeline_stage_latency_seconds` — histogram per stage name (find which stage is slow)
  - `redis_latency_seconds` — histogram (is Redis the bottleneck?)
  - `frequency_cap_hit_total` — counter (how often are we capping? too high = lost revenue)
  - `budget_exhausted_total` — counter per campaign (how fast are campaigns spending?)
- `health/HealthCheck.java` — interface
- `health/RedisHealthCheck.java`, `health/KafkaHealthCheck.java`
- `health/CompositeHealthCheck.java` — aggregate all checks
- Wire `GET /metrics` → Prometheus scrape endpoint
- Wire `GET /health` → CompositeHealthCheck

**What you learn**: Micrometer metrics model (counters, histograms, gauges). Why histogram for latency (captures percentiles). Health check pattern for container orchestration (K8s liveness/readiness probes). RED method: Rate, Errors, Duration.

**Test**: `curl localhost:8080/metrics` → Prometheus format. `curl localhost:8080/health` → JSON status of each dependency.

**Commit**: `phase-9: observability — prometheus metrics + health checks`

---

## Phase 10: Resilience — Circuit breakers + timeouts
**Goal**: System degrades gracefully when Redis/Kafka are slow or down.

**What you build**:
- `resilience/CircuitBreaker.java` — simple state machine: closed → open (after N failures) → half-open (test after cooldown)
- `resilience/Timeout.java` — wrap any async operation with a deadline
- `resilience/Fallback.java` — default values when dependency fails
- Wrap `RedisUserSegmentRepository` with circuit breaker + timeout + fallback (return empty segments if Redis is down)
- Wrap `KafkaEventPublisher` with circuit breaker (drop events if Kafka is down, don't crash)

**What you learn**: Circuit breaker pattern — why it exists (prevent cascading failure). Timeout pattern — why "no timeout = infinite wait = thread exhaustion = system death." Graceful degradation — serve partial results instead of crashing.

**Test**: Stop Redis container. Send bid → still responds (with fallback segments, logged warning). Start Redis → circuit closes, normal behavior resumes.

**Commit**: `phase-10: resilience — circuit breakers, timeouts, graceful degradation`

---

## Phase 11: Jackson Streaming + Zero-Alloc — Performance optimization
**Goal**: Replace any ObjectMapper usage with Jackson Streaming API. Pool BidContext objects. Minimize allocations on hot path.

**What you build**:
- `codec/BidRequestCodec.java` — `JsonParser` streaming: parse token-by-token into BidContext fields directly
- `codec/BidResponseCodec.java` — `JsonGenerator` streaming: write response directly to output buffer
- Object pool for `BidContext` — acquire/release instead of new/GC
- Pre-allocated `AdCandidate[]` array in BidContext (reuse across requests)

**What you learn**: Jackson Streaming API — how `JsonParser.nextToken()` works vs `ObjectMapper.readValue()`. Object pooling pattern — why it matters for GC. How to verify with async-profiler allocation flame graph.

**Test**: JMH benchmark before/after. async-profiler allocation flame graph: before shows thousands of allocations per request, after shows near-zero.

**Commit**: `phase-11: zero-alloc hot path — jackson streaming + object pooling`

---

## Phase 12: Load Testing — Prove it with numbers
**Goal**: Real load test with k6. Capture latency percentiles, throughput curve, find the saturation point.

**What you build**:
- `load-test/k6-load-test.js` — ramp: 100 → 1K → 10K → 50K virtual users
- `load-test/k6-spike-test.js` — sudden 10x spike
- `load-test/sample-bid-request.json` — realistic bid request
- Run tests, capture results
- async-profiler CPU flame graph under load
- GC log analysis: `-Xlog:gc*`

**What you produce**:
- `results/latency-report.md` — p50, p99, p999, max at each QPS level
- `results/throughput-curve.md` — QPS vs latency (find the knee)
- `results/flamegraphs/` — CPU + allocation flame graphs
- `results/gc-analysis.md` — ZGC pause times, allocation rate

**What you learn**: How to load test properly (k6 handles coordinated omission). How to read a flame graph. How to analyze GC logs. Where the actual bottleneck is (spoiler: probably Redis latency, not your Java code).

**Commit**: `phase-12: load testing — k6 results, flame graphs, GC analysis`

---

## Phase 13: PostgreSQL + ClickHouse — Full data layer (stretch)
**Goal**: Campaigns from PostgreSQL. Events flowing to ClickHouse for analytics.

**What you build**:
- `docker/init-postgres.sql` — campaign schema + seed data
- `repository/PostgresCampaignRepository.java` — Vert.x reactive PG client
- `CachedCampaignRepository.java` wraps it — decorator pattern
- Add ClickHouse to docker-compose
- `docker/clickhouse-schema.sql` — bid_events table
- Kafka → ClickHouse sink (or direct insert from a consumer)
- Sample ClickHouse queries: win rate, fill rate, revenue per publisher

**What you learn**: Decorator pattern in action (cached wrapper). Reactive PostgreSQL client. ClickHouse columnar analytics — why it's fast for aggregations. How events flow: bid → Kafka → ClickHouse → dashboard.

**Commit**: `phase-13: full data layer — PostgreSQL campaigns + ClickHouse analytics`

---

## Phase 14: Grafana Dashboard — Visualize everything (stretch)
**Goal**: Pre-built Grafana dashboard showing live metrics.

**What you build**:
- Add Grafana + Prometheus to docker-compose
- `docker/grafana/dashboard.json` — pre-configured panels:
  - Bid QPS (requests/sec)
  - Latency percentiles (p50, p99, p999)
  - Error rate
  - Redis latency
  - Per-stage latency breakdown
  - Budget remaining per campaign
  - Frequency cap hit rate

**What you learn**: Prometheus + Grafana stack. PromQL for percentile queries. How production ad-tech dashboards look.

**Commit**: `phase-14: grafana dashboard — live QPS, latency, win rate visualization`

---

## Phase 15: ML-Driven Quality Throttling
**Goal**: Use the pCTR score from the ML scorer to throttle low-quality bids, saving budget for high-value opportunities. Completes the "ML-driven throttling" item parked in Phase 7.

**What you build**:
- `pacing/QualityThrottledBudgetPacer.java` — decorator wrapping any `BudgetPacer`. Reads the winner's score from `AdCandidate` (already set by `ScoringStage`) and decides whether to attempt the spend.
  - `score >= highThreshold` → always spend
  - `score < lowThreshold` → always skip (save budget)
  - Middle → probabilistic spend, linearly interpolated
- `pacing/BudgetPacer.java` — added `trySpend(id, amount, score)` default method for backward compatibility
- `pipeline/stages/BudgetPacingStage.java` — passes winner's score to the new trySpend overload
- Wire via `pacing.quality.throttling.enabled=true` in config (default: disabled)

**What you learn**: How ML scores feed back into budget decisions — the pCTR flowing from Scoring → Ranking → Pacing via `AdCandidate.score`. Why decorator pattern scales: five layers of pacing composition (local/distributed → hourly → quality) with zero pipeline changes. When to enable throttling (ML scorer + tight budgets) and when not to (feature-weighted scorer — scores aren't real pCTR).

**Test**: Set `SCORING_TYPE=ml PACING_QUALITY_THROTTLING_ENABLED=true`. Send mixed-quality requests, observe low-pCTR skipped (HTTP 204 with `BUDGET_EXHAUSTED`), high-pCTR served.

**Commit**: `phase-15: ML-driven quality throttling — pCTR-based pacer decorator`

---

## Phase Summary

| Phase | What works after this | Key pattern / ad-tech concept |
|-------|----------------------|-------------------------------|
| 1 | All endpoints respond (bid, win, impression, click, health) | Vert.x, OpenRTB protocol, no-bid handling |
| 2 | Pipeline executes stages with SLA timeout | Pipeline pattern, deadline enforcement |
| 3 | User segments from Redis | Repository pattern, Lettuce async |
| 4 | Campaigns matched to users by targeting rules | Targeting engine, Strategy + Decorator pattern |
| 4.5 | **(stretch)** Embedding-based targeting for AI-chat context | Semantic retrieval, cosine similarity, embeddings |
| 5 | Over-exposed ads filtered out | Frequency capping (Redis INCR + TTL) |
| 6 | Best ad selected, bid floor enforced | Scoring, bid floor, no-bid when no eligible ads |
| 6.5 | **(stretch)** ML scoring with XGBoost pCTR model | ONNX inference, feature engineering, A/B testing |
| 7 | Budget enforced, stops when exhausted | Atomic budget pacing, distributed state |
| 8 | Full event lifecycle: bid → win → impression → click | Event-driven, Kafka, ad billing lifecycle |
| 9 | Metrics visible: fill rate, latency, per-stage breakdown | Observability, RED method, business metrics |
| 10 | Survives Redis/Kafka failures gracefully | Circuit breaker, timeout, graceful degradation |
| 11 | Minimal GC pressure on hot path | Zero-alloc, Jackson Streaming, object pooling |
| 12 | Performance proven with real numbers | Load testing (k6), flame graphs, GC analysis |
| 13 | Full data layer: PostgreSQL campaigns + ClickHouse analytics | Decorator pattern, columnar analytics |
| 14 | Live Grafana dashboard | Prometheus, PromQL, production dashboards |
| 15 | ML pCTR scores throttle low-quality bids | Quality-based pacing, decorator stacking, Scorer+Pacer coordination |

**Phases 1-7**: Core bidder — targeting, capping, scoring, pacing. ~2 days.
**Phases 8-10**: Production features — events, metrics, resilience. ~1 day.
**Phases 11-12**: Performance optimization + proof with numbers. ~1 day.
**Phases 13-14**: Full data layer + dashboards. ~1 day.
**Phases 4.5, 6.5**: AI-native targeting + ML scoring (stretch — when core is solid).

## Test Data Strategy

### Available Open-Source Data

| Source | What it provides | How we use it |
|--------|-----------------|--------------|
| [openrtb/examples (GitHub)](https://github.com/openrtb/examples) | Official OpenRTB sample bid requests and responses | Reference for our request/response schema. Copy structure. |
| [Rill OpenRTB Demo (GitHub)](https://github.com/rilldata/open-rtb-demo) | Week of sampled programmatic bid stream data (Auctions + Bids) | Real bid data patterns. Use to model realistic test distributions. |
| [Google Authorized Buyers samples](https://developers.google.com/authorized-buyers/rtb/openrtb-guide) | Sample mobile web, mobile app, display bid requests (OpenRTB 2.5) | Copy real request formats for our test payloads. |
| [BidSwitch Protocol examples](https://protocol.bidswitch.com/standards/bid-request-examples.html) | JSON bid request examples from a real exchange | Additional test scenarios: video, native, banner formats. |

### What We Generate Ourselves

The open-source datasets give us request format references, but for load testing we need volume. We generate:

| Data | How to generate | Volume |
|------|----------------|--------|
| **User segments** | `init-redis.sh` script: 100K users, each with 3-8 random segments from a pool of 50 (e.g., "sports", "tech", "travel", "age_25_34") | 100K users in Redis Sets |
| **Campaigns** | `init-campaigns.json` or `init-postgres.sql`: 100 campaigns with targeting rules, budgets ($100-$10,000), bid floors ($0.10-$2.00), frequency caps (3-10/hour), creatives | 100 campaigns |
| **Bid requests for k6** | k6 script generates random requests: random user_id from pool, random app, random device, random geo | Millions of requests at various QPS |
| **Zipfian distribution** | Some users are "hot" (hit frequently), most are "cold" — realistic access pattern | Matches real-world traffic patterns |

### Testing Approach Per Phase

| Phase | How to test | What to verify |
|-------|-----------|---------------|
| 1-2 | `curl` with sample OpenRTB JSON | Correct response format, 204 for no-bid, per-stage logs |
| 3-7 | `curl` with seeded user_ids | Correct targeting, capping, scoring, budget behavior |
| 8 | `curl` + Kafka consumer | Events appear in correct Kafka topics |
| 9-10 | `curl /metrics`, `curl /health`, stop Redis | Metrics export correctly, health degrades, circuit breaker activates |
| 11-12 | k6 load test at 1K → 10K → 50K QPS | Latency percentiles, throughput curve, flame graphs, GC analysis |
