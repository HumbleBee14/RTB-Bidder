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
  │  FrequencyCapStage ◄── Redis GET (read-only, per candidate)    │
  │       │                                                        │
  │       │  freq:user_00042:camp-001 → GET → 5 ≥ max(5) → SKIP    │
  │       │  freq:user_00042:camp-002 → GET → 2 < max(8) → KEEP    │
  │       │  freq:user_00042:camp-008 → GET → 1 < max(7) → KEEP    │
  │       │                                                        │
  │       │  0 candidates left? → abort(ALL_FREQUENCY_CAPPED)      │
  │       └─ 2 candidates remain                                   │
  │       ▼                                                        │
  │  ResponseBuildStage (picks winner, builds response)            │
  │                                                                │
  └────────────────────────────────────────────────────────────────┘
```

## Redis Data Model

```
Key:    freq:{userId}:{campaignId}
Type:   String (counter)
TTL:    3600 seconds (1 hour window)

Check (FrequencyCapStage — read-only):
  GET freq:user_00042:camp-001     → returns current count (or nil)

Record (BidRequestHandler — after 200 sent, winner only, Lua script):
  EVAL "INCR + EXPIRE if new" freq:user_00042:camp-001 3600
```

## Files

| File | Purpose |
|------|---------|
| `frequency/FrequencyCapper.java` | Interface — `isAllowed()` (read) + `recordImpression()` (write) |
| `frequency/RedisFrequencyCapper.java` | GET for check, Lua script for atomic INCR+EXPIRE |
| `pipeline/stages/FrequencyCapStage.java` | Read-only filter — does not increment counters |

## Design Decisions

### Check vs Record — split to avoid overcounting

FrequencyCapStage checks all candidates (read-only GET). BidRequestHandler records only the winner (INCR) **after** the 200 response is sent. This ensures we never increment for bids that failed to send (SLA timeout, serialization error). If we incremented inside the pipeline, a post-loop SLA abort would overcount.

### Lua script for atomic INCR + EXPIRE

A bare INCR followed by EXPIRE is two commands — if the process crashes between them, the key loses its TTL and caps the user forever. The Lua script runs INCR + conditional EXPIRE atomically in one Redis round-trip.

### One Redis round-trip per candidate

With 3-5 candidates, that's 3-5 sequential Redis calls. At ~0.5ms per call, that's 1.5-2.5ms — significant in a 50ms SLA budget. Phase 10 (resilience) adds batching via Redis pipelining to reduce this to 1 round-trip regardless of candidate count.

## End-to-End Test Results (Phases 1-5)

All stages running with real Redis — tested and verified:

```
Pipeline: [RequestValidation: 0.009ms, UserEnrichment: 3.019ms, CandidateRetrieval: 0.047ms,
           FrequencyCap: 13.197ms, ResponseBuild: 12.568ms] total=29.416ms deadline=50ms bid=true
```

| Test | Result |
|------|--------|
| user_00042 (fitness segment) | 200 — Nike camp-001, $0.75 |
| user_00001 (shopping, age_18_24) | 200 — GameZone camp-005, $0.50 |
| Unknown user (no segments) | 204 NO_MATCHING_CAMPAIGN |
| Missing user_id | 204 NO_MATCHING_CAMPAIGN |
| Empty ad_slots | 204 NO_MATCHING_CAMPAIGN |
| Negative bid_floor | 204 NO_MATCHING_CAMPAIGN |
| Exchange floor $2.00 > campaign $0.75 | 200 — price: $2.00 (bid floor enforced) |
| Win notification | 200 acknowledged |
| Impression pixel | 200 (1x1 GIF) |
| Click tracking | 200 click_tracked |

Different users get different ads based on their segments. Bid floor enforcement works. No-bid flows work for all validation failures.

## How to test

```bash
docker compose up -d redis
bash docker/init-redis.sh | docker exec -i java-rtb-bidder-redis-1 redis-cli

mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Different users → different ads
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"

# Frequency cap test — hit same user repeatedly
for i in 1 2 3 4 5 6; do
  echo "=== Request $i ==="
  curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"
  echo
done
```
