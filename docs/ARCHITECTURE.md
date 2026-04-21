# RTB Bidder — Complete Architecture Reference

> A production-grade Real-Time Bidding engine built in Java 21. This document explains every component,
> every design decision, and the complete end-to-end flow from an HTTP request to a bid response in ≤50ms.

---

## Table of Contents

1. [What Is RTB? The Business Context](#1-what-is-rtb-the-business-context)
2. [System Overview](#2-system-overview)
3. [Infrastructure Stack](#3-infrastructure-stack)
4. [End-to-End Request Flow](#4-end-to-end-request-flow)
5. [The 8-Stage Pipeline — Deep Dive](#5-the-8-stage-pipeline--deep-dive)
6. [Module Reference](#6-module-reference)
   - [server](#61-server--http-layer)
   - [pipeline](#62-pipeline--orchestrator)
   - [repository](#63-repository--data-access)
   - [targeting](#64-targeting--candidate-filtering)
   - [scoring](#65-scoring--bid-price-computation)
   - [frequency](#66-frequency--exposure-capping)
   - [pacing](#67-pacing--budget-management)
   - [event](#68-event--kafka-streaming)
   - [resilience](#69-resilience--fault-tolerance)
   - [metrics](#610-metrics--observability)
   - [codec](#611-codec--zero-allocation-serialization)
   - [config](#612-config--configuration)
   - [health](#613-health--dependency-health-checks)
7. [Data Models](#7-data-models)
8. [Performance Engineering Decisions](#8-performance-engineering-decisions)
9. [Observability Stack](#9-observability-stack)
10. [Full Event Lifecycle (Bid → Win → Impression → Click)](#10-full-event-lifecycle)
11. [Resilience & Fault Tolerance](#11-resilience--fault-tolerance)
12. [Configuration Reference](#12-configuration-reference)
13. [How the Pieces Fit Together — Wiring in Application.java](#13-how-the-pieces-fit-together)

---

## 1. What Is RTB? The Business Context

Real-Time Bidding is the auction mechanism that decides **which ad you see** when you load a webpage or open an app. It happens in under 100 milliseconds — while the page is still loading.

```
User opens webpage
       │
       ▼
  Publisher (website/app)
  sends Bid Request →→→→→→→→→→ [THIS SYSTEM] RTB Bidder
       │                              │
       │                    Evaluates: Who is this user?
       │                              Which campaigns match?
       │                              What is the best bid?
       │                              Is the user overexposed?
       │                              Does the campaign have budget?
       │                              │
       │◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄◄│ Returns: Bid Response (price, ad markup)
       │
  Auction: Highest bidder wins
       │
  Ad is shown to user
       │
  Win/Impression/Click events flow back →→ Kafka →→ ClickHouse (analytics)
```

### Key Constraints

| Constraint | Value | Why |
|---|---|---|
| Bid deadline (SLA) | 50ms | Publisher waits max 100ms; network takes ~30-50ms |
| Throughput | 50,000+ req/s | Peak ad traffic on a major publisher |
| Memory budget | 512MB heap | Predictable, no GC pressure from heap growth |
| Availability | 99.99% | Ad not shown = revenue lost (no retries) |

---

## 2. System Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           RTB BIDDER  (port 8080)                               │
│                                                                                 │
│   ┌────────────┐    ┌─────────────────────────────────────────────────────┐     │
│   │  Vert.x    │    │               BID PIPELINE (8 stages)               │     │
│   │  HTTP      │───►│  Validate → Enrich → Filter → FreqCap → Score →     │     │
│   │  Server    │    │  Rank → PacingCheck → BuildResponse                 │     │
│   │ (Netty)    │◄───│                                                     │     │
│   └────────────┘    └─────────────────────┬───────────────────────────────┘     │
│                                           │                                     │
│   ┌───────────────────────────────────────┼───────────────────────────────┐     │
│   │                          SERVICES     │                               │     │
│   │                                       │                               │     │
│   │  ┌──────────────┐  ┌───────────────┐  │  ┌──────────────────────────┐ │     │
│   │  │  Targeting   │  │   Scoring     │  │  │     Budget Pacing        │ │     │
│   │  │  Engine      │  │   Engine      │  │  │  (local/distributed/     │ │     │
│   │  │ (segment/    │  │ (feature/ml/  │  │  │   hourly/quality)        │ │     │
│   │  │  embedding/  │  │  abtest/      │  │  └──────────────────────────┘ │     │
│   │  │  hybrid)     │  │  cascade)     │  │                               │     │
│   │  └──────────────┘  └───────────────┘  │  ┌──────────────────────────┐ │     │
│   │                                       │  │   Frequency Capper       │ │     │
│   │  ┌──────────────┐  ┌───────────────┐  │  │   (Redis INCR)           │ │     │
│   │  │  Campaign    │  │  User Segment │  │  └──────────────────────────┘ │     │
│   │  │  Repository  │  │  Repository   │  │                               │     │
│   │  │ (JSON/PG +   │  │  (Redis)      │  │  ┌──────────────────────────┐ │     │
│   │  │  LRU cache)  │  └───────────────┘  │  │   Circuit Breakers       │ │     │
│   │  └──────────────┘                     │  │  (Redis + Kafka)         │ │     │
│   └───────────────────────────────────────┼──┴──────────────────────────┘─┘     │
│                                           │                                     │
└───────────────────────────────────────────┼─────────────────────────────────────┘
                                            │
    ┌───────────────────────────────────────▼─────────────────────────────────────┐
    │                         EXTERNAL INFRASTRUCTURE                             │
    │                                                                             │
    │  ┌──────────┐  ┌──────────┐  ┌─────────────┐  ┌──────────┐  ┌───────────┐   │
    │  │  Redis   │  │  Kafka   │  │ PostgreSQL  │  │ClickHouse│  │Prometheus │   │
    │  │          │  │          │  │             │  │          │  │+ Grafana  │   │
    │  │ Segments │  │ Events   │  │  Campaigns  │  │Analytics │  │ Dashboards│   │
    │  │ FreqCaps │  │ (async)  │  │  (startup   │  │ OLAP     │  │           │   │
    │  │ Budgets  │  │          │  │   cached)   │  │          │  │           │   │
    │  └──────────┘  └──────────┘  └─────────────┘  └──────────┘  └───────────┘   │
    └─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Infrastructure Stack

| Layer | Technology | Role | Why This Choice |
|---|---|---|---|
| **HTTP Server** | Vert.x 4.5 on Netty | Non-blocking I/O, event-loop model | 50K+ concurrent connections without threads per request |
| **User Data** | Redis 7 (Lettuce async client) | Segments, frequency counts, distributed budgets | Sub-millisecond reads; Lettuce is fully async (non-blocking) |
| **Event Bus** | Kafka 3.7 | Async bid/win/impression/click events | Decouples bidding from analytics; fire-and-forget |
| **Campaigns** | PostgreSQL 16 + in-memory cache | Campaign definitions, loaded at startup | Hot path never touches PG; 60s refresh from in-memory LRU |
| **Analytics** | ClickHouse 24 | OLAP queries on billions of events | MergeTree + Kafka engine for streaming ingestion |
| **Metrics** | Prometheus + Micrometer | Time-series metrics scraping | Pull model; Micrometer is vendor-neutral |
| **Dashboards** | Grafana 11 | Live monitoring panels | Pre-provisioned with 11+ panels; no manual setup |
| **JVM** | Java 21 + ZGC Generational | Low-latency GC | ZGC pauses <1ms even at 512MB heap |
| **ML Runtime** | ONNX Runtime 1.19 | pCTR model inference | Language-neutral; Python trains, Java infers |

### Docker Compose Services

```
docker compose up -d
        │
        ├── redis:7-alpine           :6379   → user segments, freq caps, budgets
        ├── apache/kafka:3.7.0       :9092   → bid/win/impression/click events
        ├── postgres:16-alpine       :5432   → campaign definitions (startup only)
        ├── clickhouse/clickhouse-server:24  :8123/:9000  → analytics OLAP
        ├── prom/prometheus:v2.53.0  :9090   → metrics scraping every 15s
        └── grafana/grafana:11.1.0   :3000   → live dashboards
```

---

## 4. End-to-End Request Flow

### 4.1 Single Bid Request — Happy Path

```
POST /bid  (JSON body: BidRequest)
    │
    ▼
┌──────────────────────────────────────────────────────────────────────┐
│  Vert.x HTTP Server (non-blocking, Netty event loop)                 │
│  BidRequestHandler.handle()                                          │
│  • Reads body bytes (max 65KB)                                       │
│  • Calls BidRequestCodec.decode() → BidRequest POJO                  │
│  • Creates BidContext from pool (zero allocation!)                   │
│  • Submits to BidPipeline.process()                                  │
└────────────────────────────┬─────────────────────────────────────────┘
                             │  BidContext (mutable, pooled object)
                             ▼
┌───────────────────────────────────────────────────────────────────────┐
│  BidPipeline.process()                                                │
│  • Records start timestamp                                            │
│  • Iterates 8 stages in order                                         │
│  • After EACH stage: checks deadline (System.nanoTime() - start)      │
│  • If deadline exceeded: throws PipelineException(TIMEOUT)            │
│  • If stage returns SKIP: skips remaining (no-bid fast path)          │
└────────────────────────────┬──────────────────────────────────────────┘
                             │
        ┌────────────────────┼──────────────────────────────────┐
        │                    │ 8 stages run sequentially        │
        ▼                    ▼                                  ▼
   Stage 1             Stage 2                             Stage 3
   RequestValidation   UserEnrichment                      CandidateRetrieval
   • Check user_id     • Redis GET user:{id}:segments      • Load ALL campaigns
   • Check ad_slots    • Deserialize segment set           • TargetingEngine.match()
   • Check bid_floor   • Store in BidContext.userSegments  • Returns List<AdCandidate>
   • Returns SKIP      • If Redis down: empty segments     • Store in BidContext
     if invalid          (circuit breaker)                 • Returns SKIP if empty

        ▼                    ▼                                  ▼
   Stage 4             Stage 5                             Stage 6
   FrequencyCap        Scoring                             Ranking
   • For each          • For each AdCandidate:             • Sort AdCandidates
     candidate:          Scorer.score(context, candidate)    by score DESC
     Redis INCR         • feature-weighted: formula        • Pick top 1 winner
     user:{id}:freq:      relevance × bid_floor × pacing  • Store in BidContext
     camp:{id}         • ml: ONNX pCTR inference            .winner
   • Remove those        float[] → pCTR score
     above cap         • Store scores in candidate

        ▼                    ▼
   Stage 7             Stage 8
   BudgetPacing        ResponseBuild
   • Check if winner   • Serialize BidResponse
     campaign has      • Build tracking URLs:
     remaining budget    /win?bid_id=...
   • Deduct bid price    /impression?bid_id=...
     atomically          /click?bid_id=...
   • Returns SKIP      • BidResponseCodec.encode()
     if exhausted      • Returns JSON bytes
                             │
                             ▼
┌───────────────────────────────────────────────────────────────────────┐
│  BidRequestHandler (back in HTTP handler)                             │
│  • Publishes BidEvent to Kafka (async, after response sent)           │
│  • Records metrics: bid_requests_total, bid_latency_seconds           │
│  • Writes HTTP 200 with BidResponse JSON                              │
│  • Returns BidContext to pool (zero-allocation cleanup)               │
└───────────────────────────────────────────────────────────────────────┘
    │
    ▼
HTTP 200 (BidResponse JSON) → Publisher auction
```

### 4.2 No-Bid Paths (when we choose NOT to bid)

Each stage can return `SKIP` which terminates the pipeline early and returns HTTP 204 No Content:

```
RequestValidation SKIP  → invalid request (no user_id, no slots, negative floor)
CandidateRetrieval SKIP → no campaigns matched user's segments
FrequencyCap SKIP       → all candidates exceeded exposure limit for this user
BudgetPacing SKIP       → winning campaign has zero budget remaining
Pipeline TIMEOUT        → total time exceeded 50ms SLA
```

### 4.3 Win Notification Flow

```
Publisher (won the auction)
    │
    POST /win  { campaign_id, clearing_price, bid_id }
    │
    ▼
WinHandler
    • Validates win notification
    • Publishes WinEvent to Kafka
    • Returns HTTP 200
```

### 4.4 Tracking Flow (Impression & Click)

```
Ad markup contains:
    <img src="http://bidder:8080/impression?bid_id=abc123" width=1 height=1>
    <a href="http://bidder:8080/click?bid_id=abc123">

When user's browser loads the ad:
    GET /impression?bid_id=abc123
        → TrackingHandler → publishes ImpressionEvent to Kafka → HTTP 200

When user clicks:
    GET /click?bid_id=abc123
        → TrackingHandler → publishes ClickEvent to Kafka → HTTP 200
```

---

## 5. The 8-Stage Pipeline — Deep Dive

```
BidRequest
    │
    │ STAGE 1: RequestValidationStage
    │   Input:  raw BidContext (BidRequest set)
    │   Checks: user_id non-null, ad_slots non-empty, bid_floor >= 0
    │   Output: CONTINUE or SKIP(INVALID_REQUEST)
    │   Latency budget: ~0.01ms (pure CPU, no I/O)
    │
    │ STAGE 2: UserEnrichmentStage
    │   Input:  BidContext with user_id
    │   Does:   Redis GET user:{user_id}:segments (async Lettuce call)
    │           Deserializes JSON array → Set<String>
    │           Stores in BidContext.userProfile.segments
    │   If Redis down (circuit OPEN): proceeds with empty segments
    │   Output: CONTINUE (never blocks — always proceeds)
    │   Latency budget: ~1-3ms (network RTT to Redis)
    │
    │ STAGE 3: CandidateRetrievalStage
    │   Input:  BidContext with userProfile.segments
    │   Does:   campaignRepository.findAll() → List<Campaign> (from in-memory cache)
    │           targetingEngine.match(campaign, userProfile) for each campaign
    │           Builds List<AdCandidate> for matching campaigns
    │   Strategies:
    │     segment:   campaign.targetSegments ∩ user.segments ≠ ∅
    │     embedding: cosine_similarity(user_embedding, campaign_embedding) > threshold
    │     hybrid:    segment first, embedding fallback
    │   Output: CONTINUE with candidates, or SKIP(NO_CANDIDATES)
    │   Latency budget: ~0.5ms (in-memory, no I/O)
    │
    │ STAGE 4: FrequencyCapStage
    │   Input:  BidContext with List<AdCandidate>
    │   Does:   For each candidate:
    │             Redis INCR user:{id}:freq:{campaign_id}:{window}
    │             If returned count > campaign.freqCapLimit → remove candidate
    │           (Uses INCR not GET+SET for atomicity — no race condition)
    │   If Redis down: skips cap check (all candidates pass)
    │   Output: CONTINUE with filtered candidates, or SKIP(FREQUENCY_CAPPED)
    │   Latency budget: ~2-5ms (N Redis calls, one per candidate)
    │
    │ STAGE 5: ScoringStage
    │   Input:  BidContext with filtered candidates
    │   Does:   Scorer.score(context, candidate) for each candidate
    │   Strategies:
    │     feature-weighted:  score = relevance_score × bid_floor × pacing_factor
    │     ml:                ONNX inference → pCTR float → score = pCTR × bid_floor
    │     abtest:            50% get ml scorer, 50% get feature-weighted (by user_id hash)
    │     cascade:           feature-weighted first; if score < threshold → ml rescore
    │   Output: CONTINUE with scored candidates
    │   Latency budget: ~1ms (CPU-only; ONNX < 5ms)
    │
    │ STAGE 6: RankingStage
    │   Input:  scored candidates
    │   Does:   Collections.sort() by score DESC
    │           Takes candidates.get(0) as winner
    │           Stores in BidContext.winner
    │   Output: CONTINUE with winner set
    │   Latency budget: ~0.01ms (tiny list, in-memory sort)
    │
    │ STAGE 7: BudgetPacingStage
    │   Input:  BidContext with winner AdCandidate
    │   Does:   budgetPacer.checkAndDeduct(campaign_id, bid_price)
    │   Strategies:
    │     local:      AtomicLong.decrementAndGet() — single JVM, fast
    │     distributed: Redis DECRBY campaign:{id}:budget bid_price_micros — multi-JVM safe
    │     hourly:     Wraps base pacer; scales daily budget / 24 per hour
    │     quality:    Wraps hourly; skips low pCTR bids (Phase 15 throttling)
    │   Output: CONTINUE if budget OK, SKIP(BUDGET_EXHAUSTED) if depleted
    │   Latency budget: ~0.5-2ms (local=0.01ms, distributed=~2ms Redis)
    │
    │ STAGE 8: ResponseBuildStage
    │   Input:  BidContext with winner and user_id
    │   Does:   Builds BidResponse POJO:
    │             bid_id  = UUID (reused from BidContext pool)
    │             price   = winner.score (normalized bid price)
    │             ad_markup = HTML creative with tracking URLs embedded
    │             win_url  = baseUrl + /win?bid_id=...&campaign_id=...
    │             impression_url = baseUrl + /impression?bid_id=...
    │             click_url = baseUrl + /click?bid_id=...
    │           BidResponseCodec.encode() → JSON bytes (streaming Jackson)
    │   Output: BidContext.responseBytes set
    │   Latency budget: ~0.5ms (Jackson streaming, no intermediate Map)
    │
    ▼
BidResponse JSON (HTTP 200) or No-Bid (HTTP 204)
```

### SLA Enforcement Mechanism

```java
// BidPipeline.java (simplified)
long deadline = startNanos + config.maxLatencyNs();  // e.g. 50_000_000 ns = 50ms

for (PipelineStage stage : stages) {
    StageResult result = stage.process(ctx);
    
    if (result == SKIP) {
        return NO_BID;      // fast exit
    }
    
    if (System.nanoTime() > deadline) {
        metrics.recordTimeout();
        return NO_BID;      // time's up
    }
}
return BUILD_RESPONSE;
```

---

## 6. Module Reference

### 6.1 `server` — HTTP Layer

**Files:** `HttpServer.java`, `BidRouter.java`, `BidRequestHandler.java`, `WinHandler.java`, `TrackingHandler.java`

**What it does:** Accepts HTTP connections and routes them to handlers. Built on Vert.x which runs on Netty. Uses event-loop concurrency — no thread-per-request. A single thread can handle thousands of concurrent connections.

**Endpoints:**

| Method | Path | Handler | Purpose |
|---|---|---|---|
| POST | /bid | BidRequestHandler | Main bidding endpoint |
| POST | /win | WinHandler | Win notification from exchange |
| GET | /impression | TrackingHandler | Impression pixel (1x1 img) |
| GET | /click | TrackingHandler | Click tracking redirect |
| GET | /health | HealthHandler | Liveness + readiness check |
| GET | /metrics | MetricsHandler | Prometheus text exposition |

**Why Vert.x over Spring MVC / Tomcat?**
- Tomcat uses 1 thread per connection → ~200 threads max
- Vert.x event-loop handles 50,000+ connections on 8 threads
- Matches our latency goal: zero thread context-switch overhead on hot path

```
Incoming connections
        │
        ▼
  Netty Boss Thread (accepts TCP connections)
        │
        ▼
  Vert.x Event Loop Threads (8 threads, one per CPU core)
        │           │
        ▼           ▼
  BidHandler  HealthHandler  ...
  (async)     (async)
```

### 6.2 `pipeline` — Orchestrator

**Files:** `BidPipeline.java`, `BidContext.java`, `BidContextPool.java`, `PipelineStage.java`, `PipelineException.java`

**What it does:** Owns the 8-stage execution loop with SLA deadline enforcement. Manages `BidContext` object pooling.

**BidContext is the "working memory" of a single bid:**

```
BidContext {
    BidRequest request          // parsed input
    UserProfile userProfile     // enriched from Redis
    List<AdCandidate> candidates // filtered campaigns
    AdCandidate winner          // chosen winner
    byte[] responseBytes        // serialized output
    long startNanos             // for latency tracking
    String bidId                // reused UUID string
}
```

**BidContextPool — why it exists:**

Every HTTP request would normally allocate a new `BidContext`. At 50,000 req/s that's 50,000 objects/second on the heap → GC pressure. Instead, we maintain a pool of pre-allocated `BidContext` objects. The handler checks one out, uses it, resets it, and returns it.

```
BidContextPool (size=256)
    │
    ├── acquire() → takes from pool (or creates new if empty)
    └── release() → resets all fields, returns to pool
```

### 6.3 `repository` — Data Access

**Files:** `CampaignRepository.java`, `JsonCampaignRepository.java`, `PostgresCampaignRepository.java`, `CachedCampaignRepository.java`, `UserSegmentRepository.java`, `RedisUserSegmentRepository.java`

**Campaign Repository — layered design:**

```
CandidateRetrievalStage
        │
        ▼
CachedCampaignRepository    ← always hit this
    │  (in-memory List<Campaign>, 60s TTL)
    │
    ▼  (on cache miss or refresh)
PostgresCampaignRepository  ← or →  JsonCampaignRepository
    │                                   │
    ▼                                   ▼
SELECT * FROM campaigns            Load campaigns.json
(startup + periodic refresh)       (startup only)
```

**Why cache campaigns in memory?**
Campaigns change infrequently (minutes-to-hours). Loading from PG on every bid request would be 1 SQL query per bid = catastrophic latency. The 60-second refresh means new campaigns are live within a minute.

**User Segment Repository:**

```java
// RedisUserSegmentRepository
// Key: user:{user_id}:segments
// Value: JSON array → ["sports", "tech", "male", "age_25_34"]
// TTL: set by data pipeline (typically 24h)

redis.get("user:" + userId + ":segments")
     .thenApply(json -> parseSegments(json))
```

Redis stores segments for 10,000+ users (seeded by `docker/init-redis.sh`).

### 6.4 `targeting` — Candidate Filtering

**Files:** `TargetingEngine.java`, `SegmentTargetingEngine.java`, `EmbeddingTargetingEngine.java`, `HybridTargetingEngine.java`

**What it does:** Given a user's profile and a campaign, decides if this campaign is eligible to bid for this user.

**Strategy 1: Segment Targeting (default)**

```
User segments:       { "sports", "male", "age_25_34", "premium" }
Campaign A targets:  { "sports", "age_25_34" }   → MATCH (intersection non-empty)
Campaign B targets:  { "luxury", "female" }      → NO MATCH (disjoint sets)
```

Algorithm: `Set.retainAll()` — O(n) where n = smaller set size.

**Strategy 2: Embedding Targeting**

Uses pre-computed dense vector embeddings (128-dim float arrays) for both users and campaigns. Computes cosine similarity:

```
cosine_similarity(user_vec, campaign_vec) = (A·B) / (|A| × |B|)

If similarity > threshold (0.3 default) → MATCH

This allows semantic matching:
  User browsed "running shoes" → user_vec close to "fitness" and "sports" campaigns
  even if they don't have "sports" segment explicitly
```

Embeddings generated by `ml/generate_embeddings.py` (word2vec or BERT-style).

**Strategy 3: Hybrid**

```
Try SegmentTargeting first
If match found → done
If no match → try EmbeddingTargeting (broader semantic net)
```

Best recall, slightly higher CPU cost.

### 6.5 `scoring` — Bid Price Computation

**Files:** `Scorer.java`, `FeatureWeightedScorer.java`, `MLScorer.java`, `ABTestScorer.java`, `CascadeScorer.java`, `FeatureSchema.java`

**What it does:** Given a matched candidate, compute a bid price.

**Strategy 1: Feature-Weighted Scoring**

```
score = relevance_score × bid_floor × pacing_factor

relevance_score: how many segments matched / total campaign segments
bid_floor:       minimum price from BidRequest (publisher's floor)
pacing_factor:   0.0–1.0, reduced when campaign is spending too fast
```

Simple, fast (~0.01ms), interpretable.

**Strategy 2: ML Scoring (pCTR)**

```
Python (offline):
    train_pctr_model.py
    Features: user_segments, time_of_day, device_type, campaign_id, bid_floor, ...
    Model: gradient boosted tree or logistic regression
    Output: model.onnx (portable ONNX format)

Java (online inference):
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    OrtSession session = env.createSession("model.onnx");
    float[] features = featureSchema.extract(context, candidate);
    OrtSession.Result result = session.run(Map.of("input", OnnxTensor.createTensor(env, features)));
    float pCTR = ((float[][]) result.get(0).getValue())[0][0];
    score = pCTR × bid_floor;
```

ONNX Runtime inference: <5ms per call (includes feature extraction).

**Why ONNX?**  
Train in Python (scikit-learn, PyTorch, XGBoost) → export to `.onnx` → run in Java without Python runtime. Portable, versioned, fast.

**Strategy 3: A/B Test Scoring**

```
hash(user_id) % 100 < 50 → use MLScorer
else                      → use FeatureWeightedScorer

Publishes experiment group in metrics so you can compare CTR/revenue
between groups in Grafana.
```

**Strategy 4: Cascade Scoring**

```
score = FeatureWeightedScorer.score(candidate)
if score < LOW_CONFIDENCE_THRESHOLD (e.g. 0.3):
    score = MLScorer.score(candidate)   # expensive but accurate
```

Fast path for obvious winners, ML only for uncertain cases. Best latency/accuracy tradeoff.

### 6.6 `frequency` — Exposure Capping

**Files:** `FrequencyCapper.java`, `RedisFrequencyCapper.java`

**What it does:** Prevents showing the same ad to the same user too many times (annoying = user tunes out = wasted impressions).

**Redis data structure:**

```
Key:   user:{user_id}:freq:{campaign_id}:{time_window}
Value: integer (count)
TTL:   set per time_window (e.g., 86400s for daily)

Example:
  Key: user:abc123:freq:nike_campaign:2026-04-20
  Value: 3    ← this user has been shown Nike's ad 3 times today
  TTL: 43200s (expires at midnight)
```

**Operation: INCR (atomic)**

```
INCR user:abc123:freq:nike_campaign:2026-04-20
→ returns new value (e.g. 4)

if 4 > campaign.freqCapLimit (e.g. 5) → cap not exceeded → allow
if 4 > campaign.freqCapLimit (e.g. 3) → cap exceeded → remove candidate
```

Using INCR (not GET + SET) is **atomic** — no race condition when multiple bid servers run in parallel.

### 6.7 `pacing` — Budget Management

**Files:** `BudgetPacer.java`, `LocalBudgetPacer.java`, `DistributedBudgetPacer.java`, `HourlyPacedBudgetPacer.java`, `QualityThrottledBudgetPacer.java`, `BudgetMetrics.java`

**What it does:** Ensures campaigns don't overspend their budget and that spending is distributed evenly across the day (pacing).

**Layer 1: Local Pacing (single server)**

```java
// Per-campaign AtomicLong in JVM memory
AtomicLong remaining = budgets.get(campaignId);
long newBalance = remaining.addAndGet(-bidPriceMicros);
if (newBalance < 0) {
    remaining.addAndGet(+bidPriceMicros);  // rollback
    return false;  // budget exhausted
}
return true;
```

Fast (~0.01ms), but not safe across multiple bidder instances.

**Layer 2: Distributed Pacing (Redis, multi-server)**

```
Redis key: campaign:{campaign_id}:budget
Value:     remaining budget in microdollars (integer)

DECRBY campaign:nike_campaign:budget 150000    ← deduct $0.15 bid
→ returns new value; if negative, INCRBY to rollback
```

All bidder instances share the same Redis counter — safe for horizontal scaling.

**Layer 3: Hourly Pacing (decorator)**

Wraps any base pacer. Spreads daily budget across 24 hours:

```
daily_budget = $1000
hourly_limit = $1000 / 24 = $41.67

At 3pm, allows spending until $41.67 for the 3pm-4pm window.
If 3pm window exhausted → SKIP (even if daily budget has remaining)
Prevents "burn all budget in morning, nothing left for evening"
```

**Layer 4: Quality Throttling (Phase 15)**

```
if candidate.pCTR < QUALITY_THRESHOLD (e.g., 0.02):
    increment low_quality_bids counter
    if low_quality_rate > 0.3:   ← 30%+ bids are low quality
        skip this bid (budget saved for better opportunities)
```

Reduces wasteful spending on low-CTR impressions. Effectively makes the ML score gate the budget.

### 6.8 `event` — Kafka Streaming

**Files:** `EventPublisher.java`, `KafkaEventPublisher.java`, `NoOpEventPublisher.java`

**Events:** `BidEvent.java`, `WinEvent.java`, `ImpressionEvent.java`, `ClickEvent.java`

**What it does:** Publishes ad events to Kafka for downstream analytics, billing, and ML training.

**Critical design principle: events NEVER block the bid response**

```java
// BidRequestHandler (simplified)
BidResponse response = pipeline.process(context);
httpResponse.end(response.toJson());    // ← respond to publisher FIRST

// THEN publish event (async, non-blocking)
eventPublisher.publish(new BidEvent(context));  // fire-and-forget
```

Kafka publish is async. Even if Kafka is slow or down, the bid response is already sent. Latency budget is not consumed.

**Kafka Topics:**

| Topic | Event | Payload |
|---|---|---|
| `bid-events` | BidEvent | user_id, campaign_id, bid_price, won, latency_ms, reason |
| `win-events` | WinEvent | campaign_id, bid_id, clearing_price, timestamp |
| `impression-events` | ImpressionEvent | bid_id, user_id, timestamp |
| `click-events` | ClickEvent | bid_id, user_id, timestamp |

**ClickHouse consumes these via Kafka Engine:**

```sql
-- ClickHouse Kafka Engine table
CREATE TABLE bid_events_kafka ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = 'bid-events',
         kafka_format = 'JSONEachRow';

-- Materialized view streams from Kafka to MergeTree storage
CREATE MATERIALIZED VIEW bid_events_mv TO bid_events AS
SELECT * FROM bid_events_kafka;
```

**NoOpEventPublisher:** Used in dev/test mode — swallows all events silently. No Kafka required for basic testing.

### 6.9 `resilience` — Fault Tolerance

**Files:** `CircuitBreaker.java`, `ResilientRedis.java`, `ResilientEventPublisher.java`

**What it does:** Prevents cascading failures when Redis or Kafka become slow or unavailable.

**Circuit Breaker — 3 states:**

```
                    failures >= threshold
CLOSED ────────────────────────────────► OPEN
(healthy, all calls pass)                (tripped, all calls short-circuit)
                                              │
                    cooldown elapsed          │
                         ◄────────────────────┘
                         │
                    HALF-OPEN
                    (probe: allow 1 call)
                         │
              call succeeds ──► CLOSED (recovered)
              call fails    ──► OPEN   (still broken)
```

**Redis circuit breaker config:**
- Failure threshold: 5 consecutive failures
- Cooldown: 10 seconds
- Degraded behavior: `UserEnrichmentStage` proceeds with empty segments; `FrequencyCapStage` skips cap checks

**Kafka circuit breaker config:**
- Failure threshold: 5 consecutive failures
- Cooldown: 30 seconds
- Degraded behavior: events silently dropped (no impact on bid path)

**Why different cooldowns?**
Redis is on the hot path (affects bid quality). Kafka is async (events can be lost). Redis gets shorter cooldown so it reconnects faster.

### 6.10 `metrics` — Observability

**Files:** `MetricsRegistry.java`, `BidMetrics.java`

**What it does:** Records performance counters and histograms, exposed at `/metrics` for Prometheus scraping.

**Key Metrics:**

| Metric Name | Type | Labels | What It Measures |
|---|---|---|---|
| `bid_requests_total` | Counter | outcome (bid/nobid/timeout/error) | Total requests by outcome |
| `bid_latency_seconds` | Histogram | — | P50/P95/P99 bid latency |
| `pipeline_stage_latency_seconds` | Histogram | stage | Per-stage latency breakdown |
| `circuit_breaker_state` | Gauge | name (redis/kafka) | 0=CLOSED, 1=OPEN, 2=HALF_OPEN |
| `frequency_cap_hits_total` | Counter | — | How often users are freq-capped |
| `budget_exhaustion_total` | Counter | campaign_id | How often campaigns run out |
| `jvm_memory_used_bytes` | Gauge | area (heap/nonheap) | JVM heap usage |
| `jvm_gc_pause_seconds` | Histogram | cause | GC pause durations |

**Prometheus scrape flow:**

```
Bidder exposes /metrics (text format)
         │
         │ every 15 seconds
         ▼
Prometheus pulls and stores time series
         │
         │ PromQL queries
         ▼
Grafana renders dashboards
```

### 6.11 `codec` — Zero-Allocation Serialization

**Files:** `BidRequestCodec.java`, `BidResponseCodec.java`

**What it does:** Converts JSON bytes ↔ Java objects without creating intermediate Maps or Strings.

**Why custom codec instead of `objectMapper.readValue()`?**

Standard Jackson creates many intermediate objects:
```
"{"user_id":"abc"}" → HashMap → JsonNode tree → BidRequest POJO
                       ↑ extra allocation      ↑ extra allocation
```

Custom codec uses Jackson Streaming API:
```java
// Direct streaming parse — no intermediate objects
JsonParser p = factory.createParser(bytes);
while (p.nextToken() != END_OBJECT) {
    String field = p.getCurrentName();
    if ("user_id".equals(field)) ctx.setUserId(p.nextTextValue());
    else if ("bid_floor".equals(field)) ctx.setBidFloor(p.nextDoubleValue());
    // ...
}
```

At 50,000 req/s, eliminating even 2-3 intermediate objects per request saves ~150,000 allocations/second → ~30% less GC pressure.

### 6.12 `config` — Configuration

**Files:** `AppConfig.java`, `RedisConfig.java`, `PostgresConfig.java`, `PipelineConfig.java`

**What it does:** Loads from `application.properties` at startup. Environment variables override properties (12-factor app style).

```
REDIS_URI=redis://localhost:6379   ← env var
    overrides
redis.uri=redis://localhost:6379   ← application.properties
```

Config is immutable after startup — no hot reload (simplicity over flexibility).

### 6.13 `health` — Dependency Health Checks

**Files:** `HealthCheck.java`, `RedisHealthCheck.java`, `KafkaHealthCheck.java`, `CompositeHealthCheck.java`

**What it does:** Provides `GET /health` endpoint with dependency status.

```json
{
  "status": "UP",
  "checks": {
    "redis": "UP",
    "kafka": "DOWN"
  }
}
```

Used by:
- Load balancer health probes (remove unhealthy instances)
- Kubernetes readiness/liveness probes
- On-call alerts (Grafana → PagerDuty)

---

## 7. Data Models

```
BidRequest                          BidResponse
├── userId: String                  ├── bidId: String
├── adSlots: List<AdSlot>          ├── campaignId: String
│   └── AdSlot                     ├── price: double
│       ├── slotId: String         ├── adMarkup: String
│       ├── width: int             ├── winUrl: String
│       ├── height: int            ├── impressionUrl: String
│       └── bidFloor: double       └── clickUrl: String
├── userAgent: String
├── ip: String
└── deviceType: String

Campaign                            AdCandidate
├── id: String                      ├── campaign: Campaign
├── name: String                    ├── adSlot: AdSlot
├── targetSegments: Set<String>     ├── score: double
├── bidFloor: double                └── pCtr: double (optional, ML scorer)
├── dailyBudget: double
├── freqCapLimit: int
├── freqCapWindow: String
└── creative: String (HTML)

UserProfile
├── userId: String
└── segments: Set<String>

WinNotification
├── bidId: String
├── campaignId: String
└── clearingPrice: double
```

---

## 8. Performance Engineering Decisions

This is the core of what makes this a "performance engineering" project. Each decision below traded complexity for speed.

### 8.1 Vert.x Event-Loop (Non-blocking I/O)

**Problem:** Traditional servlet containers use 1 thread per request. At 50,000 req/s you need 50,000 threads — impossible.

**Solution:** Event-loop model (same as Node.js, nginx). 8 threads handle all 50,000+ connections via callbacks. No thread blocking = no context switches = no scheduling overhead.

**Rule:** Nothing on the event-loop thread can block. Redis calls are async (Lettuce). Kafka sends are async. Even ONNX inference must return within milliseconds.

### 8.2 ZGC (Z Garbage Collector)

**Problem:** Stop-the-World GC pauses kill latency SLAs. Old G1GC could pause 50-200ms — longer than our entire bid SLA.

**Solution:** ZGC Generational with concurrent marking and relocation. Pauses <1ms even at full heap.

```
JVM flags:
-XX:+UseZGC
-XX:+ZGenerational
-Xms512m -Xmx512m          ← fixed heap size (no growth = no full GC)
-XX:+AlwaysPreTouch         ← allocate all pages at startup (no page fault latency)
```

### 8.3 Object Pooling (BidContext Pool)

**Problem:** 50,000 allocations/second of BidContext objects → constant GC work.

**Solution:** Pre-allocate 256 BidContext objects. Reuse them with reset between requests. GC never sees these objects.

### 8.4 Zero-Allocation Jackson Streaming

**Problem:** Standard JSON parsing creates HashMaps, JsonNode trees, String[] arrays.

**Solution:** Jackson streaming API — parse directly into typed fields, no intermediate objects.

### 8.5 In-Memory Campaign Cache

**Problem:** PostgreSQL query for every bid = ~5ms latency per bid + DB overload.

**Solution:** `CachedCampaignRepository` loads all campaigns into JVM memory at startup. 60-second background refresh. Hot path is pure in-memory iteration.

### 8.6 Atomic Redis Operations

**Problem:** Frequency capping needs `GET count → check → SET count+1`. Under concurrent load this is a race condition.

**Solution:** Redis `INCR` is atomic. No GET+SET split. Correct under any concurrency.

### 8.7 Async Kafka (Fire-and-Forget)

**Problem:** Kafka write latency (~5ms) would consume 10% of 50ms SLA if synchronous.

**Solution:** Respond to publisher first, publish event after. Kafka is never on the critical path.

### 8.8 Circuit Breakers

**Problem:** If Redis is slow (100ms per call), every bid times out. Cascading failure.

**Solution:** After 5 failures, circuit opens. All Redis calls short-circuit instantly (return empty/default). Bid path degrades gracefully. Reconnect probe after 10s cooldown.

---

## 9. Observability Stack

```
Bidder (port 8080)
    │  /metrics (Prometheus text)
    │
    ▼
Prometheus (port 9090)
    │  PromQL queries
    │
    ▼
Grafana (port 3000) — 11 live panels:
    ├── Requests per second (bid rate)
    ├── Bid fill rate (% requests that result in a bid)
    ├── Latency percentiles (P50, P95, P99)
    ├── No-bid breakdown by reason (freq_cap, no_candidate, budget, timeout)
    ├── Pipeline stage latencies (which stage is slowest?)
    ├── Circuit breaker states (Redis, Kafka)
    ├── JVM heap usage over time
    ├── GC pause duration histogram
    ├── Budget depletion rate per campaign
    ├── Frequency cap hit rate
    └── Error rate

ClickHouse (port 8123) — analytics SQL:
    ├── SELECT campaign_id, count(*) win_count, sum(clearing_price) revenue ...
    ├── SELECT user_id, count(*) impressions, count(click_id) clicks ...
    └── SELECT toHour(timestamp), avg(latency_ms) ...
```

---

## 10. Full Event Lifecycle

```
1. BID
   POST /bid → pipeline → BidResponse
   └─► BidEvent → Kafka bid-events
       • was_bid: true/false
       • campaign_id, user_id
       • bid_price, latency_ms
       • no_bid_reason (if false)

2. WIN (publisher tells us we won the auction)
   POST /win → WinHandler
   └─► WinEvent → Kafka win-events
       • campaign_id, bid_id
       • clearing_price (actual price paid, second-price auction)

3. IMPRESSION (user's browser loaded our ad)
   GET /impression?bid_id=X → TrackingHandler
   └─► ImpressionEvent → Kafka impression-events
       • bid_id, user_id, timestamp

4. CLICK (user clicked our ad)
   GET /click?bid_id=X → TrackingHandler
   └─► ClickEvent → Kafka click-events
       • bid_id, user_id, timestamp

Downstream:
   Kafka → ClickHouse (via Kafka engine)
        → Billing system (deduct from advertiser balance on win)
        → ML pipeline (impression + click labels to retrain pCTR model)
        → Campaign management (budget tracking)

KPIs computed from these events:
   Fill Rate  = bids_won / bid_requests
   Win Rate   = wins / bids
   CTR        = clicks / impressions
   CPM        = revenue / impressions × 1000
   eCPC       = revenue / clicks
```

---

## 11. Resilience & Fault Tolerance

### What happens when each dependency fails?

| Dependency Down | Impact | Behavior |
|---|---|---|
| Redis | No user segments; no freq capping; no distributed budget | UserEnrichmentStage returns empty profile; FrequencyCapStage skips; LocalBudgetPacer used as fallback |
| Kafka | Events not recorded | Bids continue; events dropped silently; circuit breaker stops retry flood |
| PostgreSQL | Can't refresh campaigns | Serves stale cache until restart; logs WARNING |
| ClickHouse | No analytics dashboards | Zero impact on bidding |
| Prometheus | No metrics | Zero impact on bidding |

### Circuit Breaker State Transitions

```
                ┌─────────────────────────────────┐
                │                                 │
                │          CLOSED                 │
                │     (normal operation)          │
                │   all Redis/Kafka calls pass    │
                │                                 │
                └─────────────────┬───────────────┘
                                  │
                    5 consecutive failures
                                  │
                                  ▼
                ┌─────────────────────────────────┐
                │                                 │
                │           OPEN                  │
                │      (circuit tripped)          │
                │   all calls short-circuit       │
                │   immediately (return default)  │
                │                                 │
                └─────────────────┬───────────────┘
                                  │
                    cooldown elapsed (10s/30s)
                                  │
                                  ▼
                ┌──────────────────────────────────┐
                │                                  │
                │         HALF-OPEN                │
                │       (probe state)              │
                │   allow 1 real call through      │
                │                                  │
                └────────┬──────────────┬──────────┘
                         │              │
                    success           failure
                         │              │
                         ▼              ▼
                      CLOSED          OPEN
```

---

## 12. Configuration Reference

| Property | Default | Description |
|---|---|---|
| `server.port` | 8080 | HTTP listen port |
| `pipeline.max_latency_ms` | 50 | Bid SLA deadline |
| `pipeline.context_pool_size` | 256 | BidContext pool size |
| `redis.uri` | redis://localhost:6379 | Redis connection |
| `redis.command_timeout_ms` | 50 | Max Redis call time |
| `campaigns.source` | json | `json` or `postgres` |
| `targeting.type` | segment | `segment`, `embedding`, `hybrid` |
| `scoring.type` | feature-weighted | `feature-weighted`, `ml`, `abtest`, `cascade` |
| `pacing.type` | local | `local`, `distributed` |
| `events.type` | noop | `noop`, `kafka` |
| `resilience.redis.failure_threshold` | 5 | Trips circuit after N failures |
| `resilience.redis.cooldown_seconds` | 10 | Seconds before retry |

All properties overridable by environment variables (uppercase, dots → underscores):
```
PIPELINE_MAX_LATENCY_MS=30
REDIS_URI=redis://prod-redis:6379
SCORING_TYPE=ml
```

---

## 13. How the Pieces Fit Together

`Application.java` is the composition root — the single place where all objects are created and wired. No dependency injection framework. Explicit constructor injection only.

```
Application.main()
    │
    ├── AppConfig.load()              ← properties + env vars
    │
    ├── ObjectMapper (Jackson)        ← shared, thread-safe
    │
    ├── RedisUserSegmentRepository    ← Lettuce async connection
    ├── RedisFrequencyCapper          ← same connection pool
    │
    ├── CampaignRepository            ← JsonCampaignRepository or PostgresCampaignRepository
    │   └── wrapped in CachedCampaignRepository (60s TTL)
    │
    ├── TargetingEngine               ← SegmentTargeting or Embedding or Hybrid
    ├── Scorer                        ← FeatureWeighted or ML or ABTest or Cascade
    ├── BudgetPacer                   ← Local or Distributed (+ Hourly + Quality wrappers)
    │
    ├── CircuitBreaker (redis)
    ├── CircuitBreaker (kafka)
    │
    ├── ResilientRedis                ← wraps userSegmentRepo + frequencyCapper + circuit breaker
    ├── EventPublisher                ← KafkaEventPublisher or NoOpEventPublisher
    │   └── wrapped in ResilientEventPublisher (+ circuit breaker)
    │
    ├── MetricsRegistry (Micrometer + Prometheus)
    ├── CompositeHealthCheck          ← RedisHealthCheck + KafkaHealthCheck
    │
    ├── List<PipelineStage> [8 stages wired in order]
    │   ├── RequestValidationStage()
    │   ├── UserEnrichmentStage(resilientRedis)
    │   ├── CandidateRetrievalStage(campaignRepo, targetingEngine)
    │   ├── FrequencyCapStage(resilientRedis)
    │   ├── ScoringStage(scorer)
    │   ├── RankingStage()
    │   ├── BudgetPacingStage(budgetPacer)
    │   └── ResponseBuildStage(baseUrl)
    │
    ├── BidPipeline(stages, config, metrics)
    │
    ├── BidContextPool(256)
    │
    ├── Handlers:
    │   ├── BidRequestHandler(pipeline, resilientRedis, eventPublisher, metrics)
    │   ├── WinHandler(eventPublisher)
    │   ├── TrackingHandler(eventPublisher)
    │   ├── HealthHandler(healthCheck)
    │   └── MetricsHandler(metricsRegistry)
    │
    ├── BidRouter(handlers)           ← routes URL paths to handlers
    │
    ├── HttpServer(vertx, router, port)
    │   └── server.start()            ← begins accepting connections
    │
    └── Runtime.addShutdownHook()     ← graceful drain on SIGTERM
```

Every single arrow is an explicit `new` or method call. There is no magic, no reflection, no classpath scanning. This makes the system fully readable from one file, and trivially portable to Rust or C++ where you'd do the same explicit wiring.

---

## Quick Reference: Startup Modes

```bash
# Minimal (no external services needed)
./mvnw exec:java
# Campaigns from JSON, NoOp events, local budget, segment targeting

# With Redis (enables user segments + freq capping)
docker compose up -d redis
./mvnw exec:java

# Full stack
docker compose up -d
EVENTS_TYPE=kafka CAMPAIGNS_SOURCE=postgres ./mvnw exec:java

# Production fat JAR with ZGC
./mvnw package -DskipTests
java -XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx512m \
     -jar target/rtb-bidder-1.0.0.jar
```
