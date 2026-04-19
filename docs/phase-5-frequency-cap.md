# Phase 5: Frequency Capping — Don't Annoy Users

## What was built

Redis-based frequency capping. Each user×campaign pair is tracked with a counter + TTL. If a user has seen a campaign too many times in the current hour, that campaign is filtered out. If all candidates are capped, the bidder returns no-bid with `ALL_FREQUENCY_CAPPED`.

## Request Flow (Phase 5)

```
  ┌─ BidPipeline ──────────────────────────────────────────────────┐
  │                                                                │
  │  RequestValidationStage                                        │
  │       ▼                                                        │
  │  UserEnrichmentStage ◄── Redis SMEMBERS (segments)             │
  │       ▼                                                        │
  │  CandidateRetrievalStage                                       │
  │       │  → 3 candidates matched: Nike, TechCorp, HealthPlus    │
  │       ▼                                                        │
  │  FrequencyCapStage ◄── Redis INCR + EXPIRE (per candidate)     │
  │       │                                                        │
  │       │  freq:user_00042:camp-001 → INCR → 6 > max(5) → SKIP  │
  │       │  freq:user_00042:camp-002 → INCR → 2 ≤ max(8) → KEEP  │
  │       │  freq:user_00042:camp-008 → INCR → 1 ≤ max(7) → KEEP  │
  │       │                                                        │
  │       │  0 candidates left? → abort(ALL_FREQUENCY_CAPPED)      │
  │       └─ 2 candidates remain                                   │
  │       ▼                                                        │
  │  ResponseBuildStage                                            │
  │                                                                │
  └────────────────────────────────────────────────────────────────┘
```

## Redis Data Model

```
Key:    freq:{userId}:{campaignId}
Type:   String (counter)
TTL:    3600 seconds (1 hour window)

Commands per candidate:
  INCR freq:user_00042:camp-001    → returns current count
  EXPIRE freq:user_00042:camp-001 3600  → set TTL on first INCR only
```

INCR is atomic — no race condition between concurrent requests for the same user. EXPIRE is set only when `count == 1` (first impression in this window) to avoid resetting the TTL on every request.

## Files

| File | Purpose |
|------|---------|
| `frequency/FrequencyCapper.java` | Interface — `isAllowed(userId, campaignId, maxImpressions)` |
| `frequency/RedisFrequencyCapper.java` | Redis INCR + EXPIRE, 1-hour window |
| `pipeline/stages/FrequencyCapStage.java` | Filters candidates, aborts if all capped |

## Design Decisions

### INCR + EXPIRE, not SETEX or Lua script

`INCR` atomically increments and returns the new count in one round-trip. We set `EXPIRE` only on the first increment (`count == 1`) to avoid resetting the window. This is 1-2 Redis commands per candidate — simple and fast.

A Lua script could combine INCR + EXPIRE into one atomic call, but adds complexity for minimal gain. At 50K QPS with 3 candidates per request, we'd be doing ~150K Redis INCR/s — well within Redis capacity (300K+ ops/s single-threaded).

### FrequencyCapStage increments on check, not on win

The counter increments when we *bid*, not when we *win*. This means the count tracks bid attempts, not confirmed impressions. In production, you'd decrement on bid loss (via POST /win not arriving) or use a separate counter for confirmed impressions. For Phase 5, counting bids is the simpler and more conservative approach — it slightly undercounts allowed impressions but never overcounts.

### One Redis round-trip per candidate

With 3-5 candidates, that's 3-5 sequential Redis calls. At ~0.5ms per call, that's 1.5-2.5ms — significant in a 50ms SLA budget. Phase 10 (resilience) adds batching via Redis pipelining to reduce this to 1 round-trip regardless of candidate count.

## How to test

```bash
# Start Redis and seed users
docker compose up -d redis
bash docker/init-redis.sh | docker exec -i <container> redis-cli

mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Hit same user 6 times — campaign with max_impressions=5 should get capped
for i in 1 2 3 4 5 6; do
  echo "=== Request $i ==="
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"
  echo
done
# After max_impressions hits: different campaign or 204 ALL_FREQUENCY_CAPPED
```
