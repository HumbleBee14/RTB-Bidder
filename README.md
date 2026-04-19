# Real-Time Bidding Engine — Java 21+ (Optimized)

A production-grade RTB bidder built with modern Java (21+), extracting every ounce of performance from the JVM — Virtual Threads, ZGC, zero-allocation hot path, async Redis, and proper load testing with real numbers.

This is NOT a Spring Boot app. No reflection, no component scanning, no annotation magic. Raw performance-first Java.

## Production Ad-Tech Tech Stack

What real ad-tech companies (Moloco, Criteo, The Trade Desk, Koah) use in production — and what we're building.

### Infrastructure Layer

| Component | What we use | What production companies use | Why this technology |
|-----------|------------|------------------------------|-------------------|
| **Cloud** | Docker Compose (local) / AWS (deploy) | AWS, GCP (Moloco uses GCP) | Moloco runs on GKE. Most ad-tech runs on AWS. Docker Compose for local dev, deploy to cloud. |
| **Container orchestration** | Docker Compose (local) | Kubernetes (GKE/EKS) | Moloco, Criteo, and most at-scale ad-tech runs on K8s. We use Compose locally, K8s-ready Dockerfile. |
| **Service mesh / LB** | Nginx (reverse proxy) | Envoy, Istio, AWS ALB | Load balance across bidder instances. Health checks. Sticky sessions if needed. |
| **Secrets / Config** | Environment variables | Vault, AWS Secrets Manager, K8s ConfigMaps | Credentials for Redis, Kafka, PostgreSQL. Env vars for local, Vault for production. |

### Data Layer

| Component | What we use | What production companies use | Why this technology |
|-----------|------------|------------------------------|-------------------|
| **Real-time cache** | **Redis 7+** | Redis (Koah, most ad-tech), Aerospike (The Trade Desk tier) | Sub-ms key-value lookups. User segments, frequency caps, budget counters. Industry standard. Aerospike for >1M TPS but Redis handles 100K+ QPS easily. |
| **User profiles & segments** | **Redis Sets** + **Roaring Bitmaps** | Redis, Aerospike, Cloud Bigtable (Moloco) | Redis Sets for "is user in segment?" lookups. Roaring Bitmaps for audience intersection (e.g., "users who are in sports AND age 25-34"). Moloco uses Bigtable for massive user profile stores. |
| **Campaign / advertiser DB** | **PostgreSQL 16** | PostgreSQL (Koah), MySQL, Cloud Spanner | ACID for campaign CRUD: create campaign, set budget, define targeting. NOT on the hot bid path — loaded into memory/Redis at startup and refreshed periodically. |
| **Event streaming** | **Apache Kafka** | Kafka (Koah, most ad-tech), Redpanda (emerging) | Every bid, impression, click, conversion → Kafka topic. Async producer — never block the bid path. Consumers process events for analytics, billing, ML training. Redpanda is a Kafka-compatible C++ alternative with lower latency. |
| **Analytics / OLAP** | **ClickHouse** | ClickHouse (Koah), BigQuery (Moloco), Druid | Real-time analytics on billions of events: win rates, fill rates, revenue per publisher, latency percentiles. ClickHouse handles 1B+ rows with sub-second queries. Columnar, compressed, blazing fast aggregations. |
| **ML feature store** | **Redis** (simple features) | Feast, Tecton, Redis, Bigtable | Features for ML scoring: user recency, ad CTR history, context embeddings. Redis for hot features. Dedicated feature stores (Feast/Tecton) for complex ML pipelines. |

### Application Layer (The Bidder — what we build in Java)

| Component | What we use | What production companies use | Why this technology |
|-----------|------------|------------------------------|-------------------|
| **Language** | **Java 21+** | Go (Moloco), Java (Criteo, many DSPs), C++ (Criteo inference), Python (ML) | Java 21 with Virtual Threads + ZGC is competitive with Go for ad serving latencies. Moloco uses Go + Java. Criteo uses C++/Java. We build Java first, C++/Rust later for comparison. |
| **HTTP server** | **Vert.x 4** (on Netty) | Netty (direct), Vert.x, Go net/http, gRPC | Vert.x = Netty's performance with a sane API. Event-loop based, 5-10x faster than Spring MVC. Handles 50K+ concurrent connections on a few threads. NOT Spring Boot — too much reflection, annotation scanning, AOP proxy overhead. |
| **GC** | **ZGC** (`-XX:+UseZGC -XX:+ZGenerational`) | ZGC (Netflix), G1 (default), Shenandoah | Generational ZGC: sub-1ms pauses regardless of heap size. Netflix migrated streaming services to it. Eliminates GC as a p99 latency factor. |
| **JSON parsing** | **Jackson Streaming API** (`JsonParser` / `JsonGenerator`) | Jackson, simdjson (C++), serde (Rust), protobuf | Jackson Streaming reads token-by-token — no object tree creation. `ObjectMapper.readValue()` creates 50+ objects per parse. Streaming creates ~0. For even faster: consider Protocol Buffers for internal service-to-service (binary, schema-based, smaller). |
| **Redis client** | **Lettuce 6** (async/reactive) | Lettuce (Java), go-redis (Go), redis-rs (Rust) | Async, non-blocking Redis client. Works with Virtual Threads. Connection pooling built-in. NOT Jedis — Jedis is synchronous, uses thread-per-connection, blocks under load. |
| **Kafka client** | **kafka-clients** (official Java client, async producer) | Official clients, librdkafka (C), Sarama (Go) | Async producer — fire-and-forget for bid events. Batching + compression for throughput. Never block the bid path waiting for Kafka ack. |
| **Metrics** | **Micrometer** + **Prometheus** | Prometheus + Grafana (Koah LGTM stack), Datadog, custom | Micrometer = vendor-neutral metrics facade (like SLF4J for metrics). Export to Prometheus. Grafana dashboards for latency percentiles, QPS, error rates, Redis latency, Kafka lag. |
| **Distributed tracing** | **OpenTelemetry** (optional, Day 3+) | Jaeger, Zipkin, Tempo (Koah uses Tempo), Datadog APM | Trace a bid request across services: bidder → Redis → Kafka → ClickHouse. Find which component adds latency. Koah uses Grafana Tempo. |

### ML / Scoring Layer

| Component | What we use | What production companies use | Why this technology |
|-----------|------------|------------------------------|-------------------|
| **ML inference** | Rule-based scoring (Day 1) → **ONNX Runtime** via Panama FFI (stretch goal) | TensorFlow (Moloco, on TPUs), ONNX Runtime, TensorRT | Day 1: simple feature-weighted scoring (`relevance × bid_floor × pacing_factor`). Production: export trained model to ONNX, serve via ONNX Runtime for sub-5ms inference. Moloco does 7ms prediction latency at 1M+ QPS. |
| **ML training** | Not in scope (offline) | Python + TensorFlow/PyTorch, trained on Kafka event streams | Training is offline: consume Kafka events → train model → export ONNX → deploy to bidder. Separate pipeline, not part of this build. |
| **Feature engineering** | Redis feature lookups | Feast, Tecton, custom feature stores | Features computed offline, stored in Redis. Bidder looks up features at bid time. |

### Testing & Observability Layer

| Component | What we use | What production companies use | Why this technology |
|-----------|------------|------------------------------|-------------------|
| **Load testing** | **k6** | k6, wrk2, Gatling, Locust, vegeta | k6 is scriptable (JavaScript), handles coordinated omission correctly, outputs HdrHistogram-compatible percentiles. wrk2 is Gil Tene's fork — also corrects coordinated omission. |
| **Micro-benchmarking** | **JMH** | JMH (Java), Google Benchmark (C++), Criterion (Rust) | JMH handles JIT warmup, dead code elimination, and fork isolation. The only correct way to micro-benchmark Java. |
| **Profiling** | **async-profiler** | async-profiler, JFR + JMC, perf (Linux) | Low-overhead CPU + allocation profiling. Generates flame graphs directly. Can attach to running process in production. |
| **GC analysis** | `-Xlog:gc*` + GCViewer | JFR, GCViewer, GCEasy | Parse GC logs to verify sub-ms pauses. Correlate GC events with p99 latency spikes. |
| **Dashboards** | **Grafana** + Prometheus + ClickHouse | Grafana (Koah LGTM stack), Datadog, custom | Real-time dashboards: bid QPS, latency percentiles (p50/p99/p999), Redis hit rate, Kafka throughput, win rate, fill rate, revenue. |

### SDK Layer (for publishers)

| Component | What we use | What production companies use | Why |
|-----------|------------|------------------------------|-----|
| **Publisher SDK** | REST API endpoint | Lightweight JS/iOS/Android SDK | Publishers integrate via REST API. The endpoint serves as the backend for any lightweight SDK. |
| **Ad format** | JSON response with ad creative URL | Native ads, display, video, in-chat | For AI apps: the ad is a text/card embedded in the chat. Response includes ad_id, creative_url, display_text. |

## Tech Stack Summary Diagram

```
┌───────────────────────────────────────────────────────────────────┐
│                        LOAD TESTING (k6)                          │
│                              │                                    │
│                              ▼                                    │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │            BIDDER (Java 21+ / Vert.x / ZGC)                 │  │
│  │                                                             │  │
│  │  Vert.x HTTP ─→ Jackson Streaming ─→ Scoring ─→ Response    │  │
│  │       │              │                  │         │         │  │
│  │       │              │                  │         │         │  │
│  └───────┼──────────────┼──────────────────┼─────────┼─────────┘  │
│          │              │                  │         │            │
│     ┌────▼────┐   ┌─────▼─────┐     ┌──────▼──────┐  │            │
│     │  Redis  │   │ PostgreSQL│     │    ONNX     │  │            │
│     │ (cache, │   │(campaigns,│     │  (ML model) │  │            │
│     │ segments│   │advertisers│     │  (stretch)  │  │            │
│     │ freq cap│   │  budgets) │     └─────────────┘  │            │
│     │ pacing) │   └───────────┘                      │            │
│     └─────────┘                                      │            │
│                                              ┌───────▼───────┐    │
│                                              │     Kafka     │    │
│                                              │  (bid events, │    │
│                                              │  impressions, │    │
│                                              │    clicks)    │    │
│                                              └───────┬───────┘    │
│                                                      │            │
│                                              ┌───────▼───────┐    │
│                                              │  ClickHouse   │    │
│                                              │ (analytics,   │    │
│                                              │  dashboards)  │    │
│                                              └───────┬───────┘    │
│                                                      │            │
│                                              ┌───────▼───────┐    │
│                                              │   Grafana +   │    │
│                                              │  Prometheus   │    │
│                                              │ (monitoring)  │    │
│                                              └───────────────┘    │
│                                                                   │
│              async-profiler ──→ Flame Graphs                      │
│              JMH ──→ Micro-benchmarks                             │
│              -Xlog:gc* ──→ GC Analysis                            │
└───────────────────────────────────────────────────────────────────┘
```

## Architecture

```
                    ┌─────────────────────────────────────────────┐
                    │           RTB Bidder (Java 21+)             │
                    │                                             │
 Bid Request ──────►│  HTTP Server (Vert.x / Virtual Threads)     │
 (JSON, <50ms)      │       │                                     │
                    │       ▼                                     │
                    │  Parse Request (Jackson Streaming)          │
                    │       │                                     │
                    │       ▼                                     │
                    │  User Targeting (Redis → segments)          │──── Redis
                    │       │                                     │
                    │       ▼                                     │
                    │  Frequency Cap Check (Redis INCR + TTL)     │
                    │       │                                     │
                    │       ▼                                     │
                    │  Candidate Retrieval (filter eligible ads)  │──── PostgreSQL (cached)
                    │       │                                     │
                    │       ▼                                     │
                    │  Scoring & Ranking (feature-weighted)       │
                    │       │                                     │
                    │       ▼                                     │
                    │  Budget Pacing (AtomicLong + Redis)         │
                    │       │                                     │
                    │       ▼                                     │
                    │  Build Response (pre-allocated buffer)      │
                    │       │                                     │
 Bid Response  ◄────│  Send Response (<10ms p99 target)           │
                    │       │                                     │
                    │       ▼ (async, non-blocking)               │
                    │  Log Event → Kafka (impression/bid log)     │──── Kafka
                    │                                             │
                    └─────────────────────────────────────────────┘
                              │                    │
                              ▼                    ▼
                         ClickHouse           Prometheus
                     (analytics/dashboards)  (metrics/alerts)
```

## Performance Targets

| Metric | Target | How to achieve |
|--------|--------|----------------|
| **p50 latency** | <5ms | Fast-path: parsed request → Redis lookup → score → respond |
| **p99 latency** | <15ms | ZGC eliminates GC spikes. Async Redis prevents blocking. Timeout on Redis (5ms). |
| **p999 latency** | <30ms | Pre-allocated buffers, no allocation on hot path. Virtual Threads prevent thread exhaustion. |
| **Throughput** | 50K+ QPS (single instance) | Vert.x event loop + Virtual Threads. Non-blocking Redis. Async Kafka producer. |
| **Memory** | <512MB heap | ZGC handles it. Minimal allocation = minimal GC pressure. |
| **CPU** | <70% at target QPS | If >70%, you're close to queueing collapse (Level 10 lesson). |

## JVM Flags

```bash
java \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms512m -Xmx512m \
  -XX:+AlwaysPreTouch \
  -XX:+UseTransparentHugePages \
  --enable-preview \
  -jar rtb-bidder.jar
```

| Flag | Why |
|------|-----|
| `-XX:+UseZGC -XX:+ZGenerational` | Generational ZGC — sub-1ms pauses, 4x throughput vs non-generational |
| `-Xms512m -Xmx512m` | Fixed heap — no resizing overhead. ZGC handles fragmentation. |
| `-XX:+AlwaysPreTouch` | Touch all heap pages at startup — no page faults during runtime. |
| `-XX:+UseTransparentHugePages` | Reduce TLB misses for heap access. |
| `--enable-preview` | Virtual Threads + other preview features. |

## Architecture Principles

| Principle | How we apply it | Why |
|-----------|----------------|-----|
| **Pipeline pattern** | Bid request flows through `PipelineStage` chain | Each stage independent, testable, swappable |
| **Interface-based design** | `Scorer`, `BudgetPacer`, `UserSegmentRepository`, `EventPublisher` | Swap implementations via config, zero code changes |
| **Repository pattern** | Data access behind interfaces | Test with fakes, deploy with Redis/PostgreSQL |
| **Decorator pattern** | `CachedCampaignRepository` wraps `PostgresCampaignRepository` | Add caching without changing interface |
| **Strategy pattern** | `Scorer` with `FeatureWeightedScorer`, `MLScorer`, `ABTestScorer` | Plug in algorithms, A/B test via config |
| **Event-driven** | Events via `EventPublisher` interface | Kafka today, Redpanda tomorrow, same interface |
| **Circuit breakers** | Redis timeout → fallback defaults | Slow dependency degrades, doesn't kill p99 |
| **Configuration-driven** | Targeting, bid floors, timeouts, flags from config | Change behavior without redeploying |

## Core Pipeline Design

```java
public interface PipelineStage {
    void process(BidContext ctx) throws PipelineException;
    String name();
}

public class BidPipeline {
    private final List<PipelineStage> stages;
    private final BidMetrics metrics;

    public BidResponse process(BidRequest request) {
        BidContext ctx = contextPool.acquire();  // reuse, not new
        ctx.reset(request);
        try {
            for (PipelineStage stage : stages) {
                long start = System.nanoTime();
                stage.process(ctx);
                metrics.recordStageLatency(stage.name(), System.nanoTime() - start);
            }
            return ctx.getResponse();
        } finally {
            contextPool.release(ctx);
        }
    }
}
```

**Extensibility**: Add geo-targeting → new `PipelineStage`. Swap scoring → new `Scorer` impl. A/B test → `ABTestScorer`. Swap Redis → new `UserSegmentRepository` impl. Zero changes to existing code.

## Interface Map

| Interface | Current impl | Swappable to |
|-----------|-------------|-------------|
| `PipelineStage` | 8 stages | Any new stage |
| `Scorer` | `FeatureWeightedScorer` | `MLScorer`, `ABTestScorer` |
| `BudgetPacer` | `LocalBudgetPacer` | `DistributedBudgetPacer` (Redis) |
| `FrequencyCapper` | `RedisFrequencyCapper` | In-memory, Aerospike |
| `UserSegmentRepository` | `RedisUserSegmentRepository` | Aerospike, Bigtable |
| `CampaignRepository` | `Cached` → `Postgres` | Any DB, config service |
| `EventPublisher` | `KafkaEventPublisher` | `NoOpEventPublisher`, Redpanda |
| `TargetingEngine` | `SegmentTargetingEngine` | `ContextTargetingEngine`, ML |
| `HealthCheck` | Redis + Kafka | Any dependency |

## Feature-to-Code Map

Where every ad-tech feature lives in the codebase. Use this as your navigation guide.

| Feature | Pipeline Stage (orchestration) | Package (implementation) | Key files | Data store | How it works |
|---------|-------------------------------|-------------------------|-----------|-----------|-------------|
| **Request validation** | `RequestValidationStage` | `pipeline/stages/` | `RequestValidationStage.java` | None | Reject malformed/oversized requests before any processing |
| **User enrichment** | `UserEnrichmentStage` | `repository/` | `RedisUserSegmentRepository.java` | Redis `SMEMBERS` | Fetch user's audience segments from Redis, attach to context |
| **Audience targeting** | `CandidateRetrievalStage` | `targeting/` | `SegmentTargetingEngine.java`, `ContextTargetingEngine.java` | In-memory (campaign cache) | Match user segments + request context against campaign targeting rules |
| **Frequency capping** | `FrequencyCapStage` | `frequency/` | `FrequencyCapper.java` (interface), `RedisFrequencyCapper.java` | Redis `INCR + EXPIRE` | "User X saw campaign Y 3x already → skip." Per user, per campaign, per time window. |
| **Ad scoring** | `ScoringStage` | `scoring/` | `Scorer.java` (interface), `FeatureWeightedScorer.java`, `MLScorer.java` | None (compute) | Score each candidate: `relevance × bid_floor × pacing_factor × recency` |
| **Ranking & selection** | `RankingStage` | `pipeline/stages/` | `RankingStage.java` | None | Sort candidates by score, pick top-K winners |
| **Budget pacing** | `BudgetPacingStage` | `pacing/` | `BudgetPacer.java` (interface), `LocalBudgetPacer.java`, `DistributedBudgetPacer.java` | `AtomicLong` (local) + Redis `DECRBY` (distributed) | Check remaining budget, atomically decrement. Don't overspend. |
| **Response building** | `ResponseBuildStage` | `pipeline/stages/` + `codec/` | `ResponseBuildStage.java`, `BidResponseCodec.java` | None | Build JSON bid response from winning candidate using Jackson Streaming |
| **Event logging** | Post-pipeline (in `BidRequestHandler`) | `event/` | `EventPublisher.java` (interface), `KafkaEventPublisher.java` | Kafka topics | Async publish bid/impression/click events. Fire-and-forget. Never blocks bid path. |
| **Campaign storage** | Pre-loaded at startup, refreshed periodically | `repository/` | `CampaignRepository.java` (interface), `PostgresCampaignRepository.java`, `CachedCampaignRepository.java` | PostgreSQL + in-memory cache | Load active campaigns from Postgres, cache in-memory, refresh every 30s |
| **Metrics** | Cross-cutting (every stage records latency) | `metrics/` | `BidMetrics.java`, `MetricsRegistry.java` | Prometheus | Per-stage latency histogram, overall QPS, error rate, cache hit rate |
| **Health checks** | Endpoint `GET /health` | `health/` | `HealthCheck.java` (interface), `RedisHealthCheck.java`, `KafkaHealthCheck.java`, `CompositeHealthCheck.java` | Pings Redis/Kafka | Aggregated health: all dependencies UP → 200 OK, any DOWN → 503 |
| **Circuit breakers** | Wraps Redis/Kafka calls | `resilience/` | `CircuitBreaker.java`, `Timeout.java`, `Fallback.java` | In-memory state machine | Redis slow → circuit opens → fallback to default segments → don't block bid path |
| **JSON parsing** | In `BidRequestHandler` before pipeline | `codec/` | `BidRequestCodec.java`, `BidResponseCodec.java`, `EventCodec.java` | None | Jackson Streaming API — zero-allocation parsing/writing |
| **Configuration** | Loaded at startup | `config/` | `AppConfig.java`, `RedisConfig.java`, `KafkaConfig.java`, `PipelineConfig.java` | Env vars / config file | All tuning knobs: timeouts, pool sizes, feature flags, A/B split percentages |

### Pipeline Flow (the order things execute)

```
HTTP Request arrives
       │
       ▼
  BidRequestHandler (parse JSON via BidRequestCodec)
       │
       ▼
  ┌─ BidPipeline ─────────────────────────────────────────────────┐
  │                                                               │
  │  1. RequestValidationStage    ← reject bad requests           │
  │  2. UserEnrichmentStage       ← Redis: fetch user segments    │
  │  3. CandidateRetrievalStage   ← match campaigns to user      │
  │  4. FrequencyCapStage         ← Redis: filter over-exposed    │
  │  5. ScoringStage              ← score each candidate          │
  │  6. RankingStage              ← sort, pick top-K              │
  │  7. BudgetPacingStage         ← check + decrement budget      │
  │  8. ResponseBuildStage        ← build bid response            │
  │                                                               │
  └───────────────────────────────────────────────────────────────┘
       │
       ▼
  BidRequestHandler (write response via BidResponseCodec)
       │
       ▼ (async, non-blocking)
  KafkaEventPublisher (publish BidEvent)
```

## Project Structure

```
java-rtb-bidder/
├── README.md
├── pom.xml
├── Dockerfile
├── docker-compose.yml
│
├── src/main/java/com/rtb/
│   │
│   ├── Application.java                        ← Wire dependencies (manual DI), start server
│   │
│   ├── server/                                  ── HTTP LAYER ──
│   │   ├── HttpServer.java                     ← Vert.x setup, Virtual Threads executor
│   │   ├── BidRouter.java                      ← POST /bid, GET /health, GET /metrics
│   │   └── BidRequestHandler.java              ← Parse → pipeline → response → publish event
│   │
│   ├── pipeline/                                ── PIPELINE (THE CORE) ──
│   │   ├── BidPipeline.java                    ← Runs BidContext through stages
│   │   ├── BidContext.java                     ← request + user + candidates + winner + response
│   │   ├── PipelineStage.java                  ← INTERFACE
│   │   └── stages/
│   │       ├── RequestValidationStage.java     ← Validate fields, format, limits
│   │       ├── UserEnrichmentStage.java        ← Redis → user segments → context
│   │       ├── CandidateRetrievalStage.java    ← Eligible campaigns via targeting
│   │       ├── FrequencyCapStage.java          ← Filter over-exposed campaigns
│   │       ├── ScoringStage.java               ← Score via Scorer strategy
│   │       ├── RankingStage.java               ← Sort by score, pick top-K
│   │       ├── BudgetPacingStage.java          ← Atomic budget check + decrement
│   │       └── ResponseBuildStage.java         ← Build BidResponse from winner
│   │
│   ├── targeting/                               ── TARGETING ──
│   │   ├── TargetingEngine.java                ← INTERFACE
│   │   ├── SegmentTargetingEngine.java         ← User segments vs campaign rules
│   │   └── ContextTargetingEngine.java         ← App, keywords, time, geo
│   │
│   ├── scoring/                                 ── SCORING (STRATEGY) ──
│   │   ├── Scorer.java                         ← INTERFACE
│   │   ├── FeatureWeightedScorer.java          ← relevance × bid_floor × pacing × recency
│   │   ├── MLScorer.java                       ← ONNX inference (stretch)
│   │   └── ABTestScorer.java                   ← Split traffic between scorers
│   │
│   ├── pacing/                                  ── BUDGET PACING ──
│   │   ├── BudgetPacer.java                    ← INTERFACE
│   │   ├── LocalBudgetPacer.java               ← AtomicLong per campaign
│   │   └── DistributedBudgetPacer.java         ← Redis DECRBY for multi-instance
│   │
│   ├── frequency/                               ── FREQUENCY CAPPING ──
│   │   ├── FrequencyCapper.java                ← INTERFACE
│   │   └── RedisFrequencyCapper.java           ← INCR + EXPIRE per user/campaign
│   │
│   ├── repository/                              ── DATA ACCESS ──
│   │   ├── UserSegmentRepository.java          ← INTERFACE
│   │   ├── RedisUserSegmentRepository.java     ← Redis SMEMBERS
│   │   ├── CampaignRepository.java             ← INTERFACE
│   │   ├── PostgresCampaignRepository.java     ← Load from PostgreSQL
│   │   └── CachedCampaignRepository.java       ← DECORATOR: in-memory + refresh 30s
│   │
│   ├── event/                                   ── EVENTS (ASYNC) ──
│   │   ├── EventPublisher.java                 ← INTERFACE
│   │   ├── KafkaEventPublisher.java            ← Async, batched, fire-and-forget
│   │   ├── NoOpEventPublisher.java             ← For testing
│   │   └── events/
│   │       ├── BidEvent.java
│   │       ├── ImpressionEvent.java
│   │       └── ClickEvent.java
│   │
│   ├── model/                                   ── DOMAIN MODELS ──
│   │   ├── BidRequest.java                     ← user_id, app_id, context, ad_slots, device
│   │   ├── BidResponse.java                    ← ad_id, creative_url, price, tracking_urls
│   │   ├── Campaign.java                       ← budget, bid_floor, targeting, creatives
│   │   ├── AdCandidate.java                    ← Campaign + score
│   │   ├── UserProfile.java                    ← user_id, segments, freq counts
│   │   └── AdContext.java                      ← App, keywords, device, geo, time
│   │
│   ├── metrics/                                 ── OBSERVABILITY ──
│   │   ├── MetricsRegistry.java                ← Micrometer + Prometheus
│   │   └── BidMetrics.java                     ← Latency, QPS, errors, cache hits
│   │
│   ├── health/                                  ── HEALTH CHECKS ──
│   │   ├── HealthCheck.java                    ← INTERFACE
│   │   ├── RedisHealthCheck.java
│   │   ├── KafkaHealthCheck.java
│   │   └── CompositeHealthCheck.java           ← All checks → GET /health
│   │
│   ├── resilience/                              ── FAULT TOLERANCE ──
│   │   ├── CircuitBreaker.java                 ← Closed → Open → Half-Open
│   │   ├── Timeout.java                        ← Wrap with timeout
│   │   └── Fallback.java                       ← Default when deps fail
│   │
│   ├── codec/                                   ── JSON (ZERO-ALLOC) ──
│   │   ├── BidRequestCodec.java                ← Jackson Streaming parser
│   │   ├── BidResponseCodec.java               ← Jackson Streaming writer
│   │   └── EventCodec.java                     ← Event serialization for Kafka
│   │
│   └── config/                                  ── CONFIGURATION ──
│       ├── AppConfig.java                      ← Central config from env / file
│       ├── RedisConfig.java                    ← URI, pool, timeouts, circuit breaker
│       ├── KafkaConfig.java                    ← Brokers, topics, batch, compression
│       ├── ServerConfig.java                   ← Port, threads, timeout, max body
│       └── PipelineConfig.java                 ← Stage order, flags, A/B split
│
├── src/test/java/com/rtb/
│   ├── pipeline/
│   │   ├── BidPipelineTest.java                ← Full pipeline with mocks
│   │   └── stages/
│   │       ├── ScoringStageTest.java
│   │       ├── FrequencyCapStageTest.java
│   │       └── BudgetPacingStageTest.java
│   ├── repository/
│   │   └── CachedCampaignRepositoryTest.java
│   ├── codec/
│   │   └── BidRequestCodecTest.java
│   ├── integration/
│   │   └── BidEndToEndTest.java                ← HTTP → pipeline → response
│   └── benchmark/
│       ├── BidPipelineBenchmark.java           ← JMH end-to-end
│       ├── JsonCodecBenchmark.java             ← Streaming vs ObjectMapper
│       ├── ScoringBenchmark.java
│       └── RedisLookupBenchmark.java
│
├── load-test/
│   ├── k6-load-test.js                         ← Ramp 100 → 1K → 10K → 50K QPS
│   ├── k6-spike-test.js                        ← Sudden 10x spike
│   ├── sample-bid-request.json
│   └── results/
│       ├── latency-report.md
│       ├── throughput-curve.md
│       ├── spike-test-results.md
│       └── flamegraphs/
│
├── docs/
│   ├── architecture.md                         ← Patterns, decisions, tradeoffs
│   ├── performance-analysis.md                 ← Bottlenecks, 10x scaling plan
│   ├── gc-analysis.md                          ← ZGC under load
│   └── scaling-strategy.md                     ← Horizontal: sharded Redis, Kafka partitions
│
└── docker/
    ├── init-redis.sh                           ← 100K users + segments
    ├── init-postgres.sql                       ← Campaigns + seed data
    ├── clickhouse-schema.sql                   ← Event tables
    └── grafana/
        └── dashboard.json                      ← QPS, latency, win rate, fill rate
```

## Zero-Allocation Hot Path Strategy

The bid handling hot path (`BidHandler.handle()`) should allocate as close to zero objects as possible:

| Operation | Naive Java (allocates) | Optimized (zero-alloc) |
|-----------|----------------------|----------------------|
| JSON parsing | `objectMapper.readValue(body, BidRequest.class)` — creates object tree | `JsonParser` streaming — read fields directly into pre-allocated buffer |
| User lookup | `new HashSet<>(segments)` per request | Redis `SISMEMBER` returns boolean — no object creation |
| Scoring | `new ArrayList<>(candidates)`, `new Score()` per ad | Pre-allocated `Score[]` array, reuse across requests |
| Response | `objectMapper.writeValueAsString(response)` — creates String | `JsonGenerator` writes directly to response buffer — no intermediate String |
| Kafka event | `new ProducerRecord<>(...)` per event | Object pool for ProducerRecord, or batch events |

**How to verify**: Run `async-profiler -e alloc` under load → allocation flame graph should show near-zero allocations in the bid path.

## Build & Run (Day 1 Target)

```bash
# Start infrastructure
docker-compose up -d redis kafka clickhouse postgres

# Seed data
docker exec -i redis redis-cli < docker/init-redis.sh
docker exec -i postgres psql -U rtb < docker/init-postgres.sql

# Build
mvn clean package -DskipTests

# Run with ZGC
java -XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx512m --enable-preview -jar target/rtb-bidder.jar

# Smoke test
curl -X POST http://localhost:8080/bid -H 'Content-Type: application/json' -d @load-test/sample-bid-request.json

# Load test
k6 run load-test/k6-load-test.js

# Profile under load
./asprof -d 30 -f flamegraph.html $(pgrep -f rtb-bidder)
```

## 3-Day Build Plan

### Day 1: Core Bidder (Working End-to-End)
- [ ] Maven project setup: Vert.x, Lettuce, Jackson, Kafka client
- [ ] `App.java`: Vert.x HTTP server with Virtual Threads
- [ ] `BidHandler.java`: Parse request → hardcoded response (prove HTTP path works)
- [ ] `docker-compose.yml`: Redis + Kafka + PostgreSQL
- [ ] `init-redis.sh`: Seed 100K users with random segments
- [ ] Wire Redis: `UserSegmentStore` → lookup user segments
- [ ] Wire scoring: Simple feature-weighted `AdScorer`
- [ ] Wire pacing: `BudgetPacer` with AtomicLong
- [ ] Wire frequency capping: `FrequencyCapper` with Redis INCR
- [ ] **End of Day 1**: Full bid path works. `curl` returns a scored bid.

### Day 2: Performance & Load Testing
- [ ] Jackson streaming parser (replace ObjectMapper if used)
- [ ] ZGC tuning: flags, verify sub-ms pauses with `-Xlog:gc*`
- [ ] k6 load test script: ramp from 100 → 1K → 10K → 50K QPS
- [ ] Run load test, capture results: latency at each QPS level
- [ ] async-profiler: CPU flame graph under load → find bottleneck
- [ ] async-profiler: allocation flame graph → verify zero-alloc hot path
- [ ] Fix bottlenecks found in profiling
- [ ] Micrometer metrics: latency histogram, QPS counter, error rate
- [ ] **End of Day 2**: Load test results with real numbers. Flame graphs. GC analysis.

### Day 3: Polish, Analytics & Documentation
- [ ] Kafka integration: async bid/impression event logging
- [ ] ClickHouse: event sink + sample dashboard queries (win rate, fill rate, latency)
- [ ] `architecture.md`: Component diagram, data flow, decisions and tradeoffs
- [ ] `performance-analysis.md`: Where time goes, what's the bottleneck, what you'd optimize at 10x scale
- [ ] `gc-analysis.md`: ZGC pause times under load, allocation rate, heap behavior
- [ ] Dockerfile: multi-stage build, production-ready
- [ ] README polish: how to run, how to test, results summary
- [ ] **End of Day 3**: Complete project with architecture doc, load test results, flame graphs, and GC analysis.

## Technical Design Decisions

| Decision | What we chose | Why | Alternative considered |
|----------|-------------|-----|----------------------|
| **Framework** | Vert.x (on Netty) | Non-blocking event loop, same transport as production ad-tech. No reflection overhead. | Spring Boot (too much per-request allocation), raw Netty (too verbose for this scope) |
| **GC** | ZGC (Generational) | Sub-1ms pauses. Netflix uses it for streaming. Eliminates GC as p99 factor. | G1 (default, higher pauses), Shenandoah (good but ZGC has better tooling) |
| **Redis client** | Lettuce (async) | Non-blocking, works with Virtual Threads, connection pooling built-in. | Jedis (synchronous, blocks under load) |
| **JSON** | Jackson Streaming API | Zero-allocation parsing. Token-by-token without creating object tree. | ObjectMapper (creates 50+ objects per parse), Gson (slower) |
| **Scoring v1** | Feature-weighted formula | Fast to implement, interpretable, good enough for v1. | ML model (better accuracy, added complexity — Phase 6.5 stretch) |
| **Scoring v2 (stretch)** | XGBoost pCTR model | Predicts click-through rate from features. Closer to how production scoring works. | Deep neural net (overkill for this scale, longer inference) |
| **Targeting v1** | Segment-based matching | Classical RTB approach, well-understood, fast. | Embedding similarity (added in Phase 4.5 stretch for AI-native targeting) |
| **Budget pacing** | AtomicLong (local) | Lock-free, zero contention for single instance. | Redis DECRBY (needed for multi-instance, added as DistributedBudgetPacer) |
| **Events** | Kafka async producer | Fire-and-forget, batched, never blocks bid path. | Direct DB insert (blocks), Redis Streams (simpler but less ecosystem) |
| **Scaling strategy** | Stateless bidder behind LB | Horizontal scale: add instances. Redis is shared state. Kafka partitioned. | Sharded bidder (complex, not needed until >100K QPS per instance) |

## Later: C++ and Rust Versions

After the Java version is solid, we rebuild in C++/Rust to see:
- Does eliminating GC entirely improve p999?
- How much does zero-cost abstraction help vs Java's JIT?
- Is the remaining gap worth the productivity tradeoff?

This comparison becomes the core of Level 9 in the performance engineering curriculum.

## References

- [Netflix on Generational ZGC](https://netflixtechblog.com/bending-pause-times-to-your-will-with-generational-zgc-256629c9386b)
- [Moloco DSP Infrastructure](https://www.moloco.com/r-d-blog/challenges-in-building-a-scalable-demand-side-platform-dsp-service)
- [OpenRTB 2.6 Specification](https://iabtechlab.com/standards/openrtb/)
- [Vert.x Documentation](https://vertx.io/docs/)
- [Lettuce (async Redis)](https://lettuce.io/)
- [k6 Load Testing](https://k6.io/)
- [async-profiler](https://github.com/async-profiler/async-profiler)
