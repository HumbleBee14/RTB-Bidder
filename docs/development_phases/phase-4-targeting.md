# Phase 4: Campaigns + Targeting — Match Users to Ads

## What was built

Campaign repository with in-memory cache, segment-based targeting engine, and CandidateRetrievalStage. The bidder now returns real matched ads based on user segments — different users get different campaigns.

## Request Flow (Phase 4)

```
  POST /bid {user_id: "user_00042", ...}
       │
       ▼
  ┌─ BidPipeline ──────────────────────────────────────────────────┐
  │                                                                │
  │  RequestValidationStage                                        │
  │       │                                                        │
  │       ▼                                                        │
  │  UserEnrichmentStage ◄── Redis SMEMBERS                        │
  │       │                  → {sports, tech, age_25_34}            │
  │       ▼                                                        │
  │  CandidateRetrievalStage                                       │
  │       │                                                        │
  │       ├─ load 10 campaigns from CachedCampaignRepository       │
  │       ├─ SegmentTargetingEngine.match()                        │
  │       │    campaign targets {sports, fitness} ∩ user {sports}   │
  │       │    → match! → AdCandidate(Nike)                        │
  │       │    campaign targets {finance} ∩ user {sports, tech}     │
  │       │    → no overlap → skip                                 │
  │       │                                                        │
  │       ├─ 0 candidates? → abort(NO_MATCHING_CAMPAIGN)           │
  │       └─ set ctx.candidates                                    │
  │       ▼                                                        │
  │  ResponseBuildStage                                            │
  │       │                                                        │
  │       └─ pick first candidate as winner → build BidResponse    │
  │          with real campaign data (id, price, creative, domain)  │
  │                                                                │
  └────────────────────────────────────────────────────────────────┘
       │
       └─ 200 {bid_id, ad_id: "camp-001", price: 0.75, creative_url: "nike-run.html", ...}
```

## Files

| File | Purpose |
|------|---------|
| `model/Campaign.java` | Campaign record — budget, bid_floor, target_segments, creative |
| `model/AdCandidate.java` | Campaign + mutable score (for ranking in Phase 6) |
| `model/AdContext.java` | Request context — app category, device, geo |
| `repository/CampaignRepository.java` | Interface |
| `repository/CachedCampaignRepository.java` | Loads campaigns from JSON, serves from memory |
| `targeting/TargetingEngine.java` | Interface |
| `targeting/SegmentTargetingEngine.java` | Matches campaigns to users via segment overlap |
| `pipeline/stages/CandidateRetrievalStage.java` | Calls targeting engine, populates candidates |
| `pipeline/stages/ResponseBuildStage.java` | Updated — builds from winning campaign, not hardcoded |
| `pipeline/BidContext.java` | Added candidates and winner fields |
| `src/main/resources/campaigns.json` | 10 campaigns with realistic targeting rules |

## Design Decisions

### Segment overlap matching — O(min(m,n)) not O(m×n)

`SegmentTargetingEngine.hasOverlap()` iterates the smaller set and checks containment in the larger. With HashSet, each `contains()` is O(1), so total is O(min(target_segments, user_segments)). For 5 target segments and 6 user segments, that's 5 hash lookups — not 30.

### CachedCampaignRepository loads once, serves from memory

Campaigns are loaded from `campaigns.json` at startup into an immutable `List.copyOf()`. No lock contention, no cache invalidation complexity. Later phases wrap this with a `PostgresCampaignRepository` + periodic refresh (decorator pattern).

### AdCandidate has mutable score, Campaign is immutable

`Campaign` is a record (immutable data). `AdCandidate` wraps a campaign with a mutable `score` field that gets set by the ScoringStage in Phase 6. This separation keeps domain data immutable while allowing pipeline-scoped computation.

## How to test

```bash
mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# User with "sports" segment → should match Nike (camp-001)
curl -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"

# User with no segments → should return 204 (no matching campaign)
curl -v -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"user_id\":\"unknown_user\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"
```

Different users return different ads based on their segments.
