# Phase 3: Docker + Redis — Real Data Store

## What was built

Redis integration via Lettuce async client. User segments stored as Redis Sets, fetched by UserEnrichmentStage in the pipeline. Docker Compose for infrastructure.

## Request Flow (Phase 3)

```
  POST /bid {user_id: "user_00042", ...}
       │
       ▼
  BidRequestHandler
       │
       ├─ parse JSON → BidRequest
       │
       ├─ pipeline.execute(request, startNanos)
       │       │
       │       ▼
       │  ┌─ BidPipeline ──────────────────────────────────────────┐
       │  │                                                        │
       │  │  RequestValidationStage                                │
       │  │       │ OK                                             │
       │  │       ▼                                                │
       │  │  UserEnrichmentStage ◄─────── Redis SMEMBERS ──────┐   │
       │  │       │                      user:user_00042:segments   │
       │  │       │                      → {sports, tech, age_25_34}│
       │  │       │                                                │
       │  │       ├─ ctx.setUserProfile(UserProfile)               │
       │  │       ▼                                                │
       │  │  ResponseBuildStage                                    │
       │  │       │                                                │
       │  │       └─ build BidResponse → ctx.setResponse()         │
       │  │                                                        │
       │  └────────────────────────────────────────────────────────┘
       │
       └─ 200 + BidResponse
```

## Redis Data Model

```
Key:    user:{user_id}:segments
Type:   Set
Values: {"sports", "tech", "age_25_34", "high_income", "urban"}

Command: SMEMBERS user:user_00042:segments
         → ["sports", "tech", "age_25_34"]
```

Why Sets? `SMEMBERS` returns all segments in O(N) where N is segment count per user (typically 3-8). `SISMEMBER` checks membership in O(1) — useful for targeting in Phase 4.

## Files

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Redis 7 (+ Postgres under `full` profile for later) |
| `docker/init-redis.sh` | Seeds 10K users with 3-8 random segments each |
| `config/RedisConfig.java` | Connection URI + timeout, validated |
| `model/UserProfile.java` | user_id + segments (immutable record) |
| `repository/UserSegmentRepository.java` | Interface |
| `repository/RedisUserSegmentRepository.java` | Lettuce sync commands, `SMEMBERS` |
| `pipeline/stages/UserEnrichmentStage.java` | Fetches segments, sets UserProfile on context |
| `pipeline/BidContext.java` | Added `userProfile` field |

## How to run

```bash
# Start Redis
docker compose up -d redis

# Seed 10K users
bash docker/init-redis.sh | docker exec -i java-rtb-bidder-redis-1 redis-cli

# Build and run
mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Test with a seeded user
curl -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"
```

Watch logs for: `UserEnrichment: 0.xxms` and the fetched segments.

## Design Decisions

### Lettuce sync commands on a shared connection (not async)

Lettuce's sync API on a `StatefulRedisConnection` is thread-safe — multiple threads share one connection. Under the hood, Lettuce pipelines commands over a single Netty channel. For our current QPS this is optimal. At 50K+ QPS, we'd switch to `RedisClusterClient` with connection pooling.

We chose sync over async for Phase 3 because the pipeline stages are synchronous. When we add Virtual Threads (later), each stage runs on a virtual thread and the sync call blocks the virtual thread (cheap), not the platform thread.

### Repository pattern — interface + implementation

`UserSegmentRepository` interface lets us:
- Swap Redis for Aerospike/Bigtable without changing the pipeline
- Use an in-memory fake for testing without Redis running
- Wrap with circuit breaker in Phase 10

### Docker Compose profiles

`docker compose up` starts only Redis. PostgreSQL is under `profiles: [full]` — it won't start until Phase 4 when we need it. No wasted resources.
