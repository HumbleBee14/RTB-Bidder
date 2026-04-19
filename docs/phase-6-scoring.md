# Phase 6: Scoring + Ranking + Multi-Slot Bidding

## What was built

Feature-weighted scoring engine, per-slot ranking with creative size matching, bid floor enforcement, campaign deduplication, and multi-slot bid response.

## Request Flow (Phase 6)

```
  ┌─ BidPipeline (7 stages) ────────────────────────────────────────┐
  │                                                                 │
  │  RequestValidationStage (shared)                                │
  │       ▼                                                         │
  │  UserEnrichmentStage (shared) ◄── Redis                         │
  │       ▼                                                         │
  │  CandidateRetrievalStage (shared) ◄── SegmentTargetingEngine    │
  │       │  → 4 candidates: Nike, GameZone, HealthPlus, EduLearn   │
  │       ▼                                                         │
  │  FrequencyCapStage (shared) ◄── Redis GET                       │
  │       │  → 3 remain (EduLearn capped)                           │
  │       ▼                                                         │
  │  ScoringStage (shared — scores once per candidate)              │
  │       │  Nike:        1/3 overlap × $0.75 = 0.25                │
  │       │  GameZone:    1/3 overlap × $0.30 = 0.10                │
  │       │  HealthPlus:  2/3 overlap × $0.60 = 0.40                │
  │       ▼                                                         │
  │  RankingStage (per-slot — size match, floor filter, dedup)      │
  │       │  slot-top (728x90, floor $0.50):                        │
  │       │    HealthPlus has 728x90, $0.60 ≥ $0.50 → WINNER       │
  │       │  slot-mid (300x250, floor $0.30):                       │
  │       │    HealthPlus already won → skip (dedup)                │
  │       │    FoodDelight has 300x250, $0.40 ≥ $0.30 → WINNER     │
  │       │  slot-sidebar (160x600, floor $0.20):                   │
  │       │    EduLearn has 160x600, $0.25 ≥ $0.20 → WINNER        │
  │       ▼                                                         │
  │  ResponseBuildStage (per-slot — builds SlotBid per winner)      │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘
       │
       └─ 200 { bids: [SlotBid(slot-top, HealthPlus), SlotBid(slot-mid, FoodDelight), ...] }
```

## Scoring Formula

```
score = segmentOverlap × bidFloor × pacingFactor

segmentOverlap: matched segments / total target segments (0.0-1.0)
bidFloor:       campaign's bid price (higher-paying ads score higher)
pacingFactor:   1.0 for now (Phase 7 uses remaining budget fraction)
```

## Files

| File | Purpose |
|------|---------|
| `scoring/Scorer.java` | Interface — `score(Campaign, UserProfile, AdContext)` |
| `scoring/FeatureWeightedScorer.java` | Weighted formula: overlap × price × pacing |
| `pipeline/stages/ScoringStage.java` | Scores candidates once (shared, not per-slot) |
| `pipeline/stages/RankingStage.java` | Per-slot: creative size match, bid floor filter, campaign dedup, pick winner |
| `pipeline/stages/ResponseBuildStage.java` | Per-slot: builds SlotBid per winner from ctx.getSlotWinners() |
| `model/BidResponse.java` | List of SlotBid (one per winning slot) |
| `model/Campaign.java` | Added creativeSizes field |

## Design Decisions

### Scoring is shared, ranking is per-slot

A campaign's score (how relevant it is to this user) doesn't change per slot. What changes per slot is: can the creative fit? Can the campaign afford the floor? Has it already won another slot? These are per-slot filters applied in RankingStage after scoring completes.

### Campaign deduplication across slots

RankingStage tracks `usedCampaigns` set. Once a campaign wins a slot, it's skipped for subsequent slots. Each slot gets a unique campaign — no user sees the same ad three times on one page.

### Bid floor enforcement in RankingStage, not ScoringStage

Bid floor is slot-dependent ($0.80 for the top banner vs $0.30 for sidebar). Since it varies per slot, it's checked during per-slot ranking, not during shared scoring.

## Test Results

| Test | Result |
|------|--------|
| 3 slots (728x90, 300x250, 160x600) | 3 different campaigns (HealthPlus, FoodDelight, EduLearn) |
| 3 identical 300x250 slots | 3 different campaigns (dedup works) |
| Single slot | 1 bid in list (no special handling) |
| All slots $5 floor | 204 NO_MATCHING_CAMPAIGN |
| Slot asks for 970x250 (no campaign has it) | 204 NO_MATCHING_CAMPAIGN |
| 2 slots, 1 matchable 1 not | Partial bid (1 SlotBid returned) |
| Unknown user | 204 NO_MATCHING_CAMPAIGN |

## How to test

```bash
mvnw.cmd clean package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Multi-slot: 3 different sizes
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"top\",\"sizes\":[\"728x90\"],\"bid_floor\":0.50},{\"id\":\"mid\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30},{\"id\":\"side\",\"sizes\":[\"160x600\"],\"bid_floor\":0.20}]}"

# High floor → no-bid
curl -v -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":5.00}]}"
```
