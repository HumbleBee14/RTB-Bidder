# Phase 6: Scoring + Ranking + Bid Floor — Pick the Best Ad at the Right Price

## What was built

Feature-weighted scoring engine, bid floor enforcement, and ranking stage. The pipeline now scores each candidate, filters those below the exchange bid floor, and picks the highest-scoring winner.

## Request Flow (Phase 6)

```
  ┌─ BidPipeline (7 stages) ───────────────────────────────────────┐
  │                                                                │
  │  RequestValidationStage                                        │
  │       ▼                                                        │
  │  UserEnrichmentStage ◄── Redis                                 │
  │       ▼                                                        │
  │  CandidateRetrievalStage ◄── SegmentTargetingEngine            │
  │       │  → 4 candidates: Nike, GameZone, HealthPlus, EduLearn  │
  │       ▼                                                        │
  │  FrequencyCapStage ◄── Redis GET                               │
  │       │  → 3 remain (EduLearn capped)                          │
  │       ▼                                                        │
  │  ScoringStage ◄── FeatureWeightedScorer                        │
  │       │  Nike:        1/3 overlap × $0.75 = 0.25               │
  │       │  GameZone:    1/3 overlap × $0.30 = 0.10               │
  │       │  HealthPlus:  2/3 overlap × $0.60 = 0.40  ← highest   │
  │       │                                                        │
  │       │  filter: campaign.bidFloor < exchange.bidFloor? → skip  │
  │       ▼                                                        │
  │  RankingStage                                                  │
  │       │  sort by score desc → pick HealthPlus (0.40)           │
  │       └─ ctx.setWinner(HealthPlus)                             │
  │       ▼                                                        │
  │  ResponseBuildStage                                            │
  │       └─ build from winner, price = max(campaign, exchange)    │
  │                                                                │
  └────────────────────────────────────────────────────────────────┘
```

## Scoring Formula

```
score = segmentOverlap × bidFloor × pacingFactor

segmentOverlap: matched segments / total target segments (0.0-1.0)
bidFloor:       campaign's bid price (higher-paying ads score higher)
pacingFactor:   1.0 for now (Phase 7 uses remaining budget fraction)
```

This is a simplified eCPM (effective cost per mille) ranking. Higher relevance AND higher price wins. A campaign with perfect segment match but low price can lose to one with partial match but high price — exactly how production ad scoring works.

## Files

| File | Purpose |
|------|---------|
| `scoring/Scorer.java` | Interface — `score(Campaign, UserProfile, AdContext)` |
| `scoring/FeatureWeightedScorer.java` | Weighted formula: overlap × price × pacing |
| `pipeline/stages/ScoringStage.java` | Scores candidates, filters below exchange bid floor |
| `pipeline/stages/RankingStage.java` | Picks highest-scoring candidate as winner |
| `pipeline/stages/ResponseBuildStage.java` | Updated — reads winner from ctx, no longer picks |

## Design Decisions

### Bid floor enforcement in ScoringStage, not ResponseBuildStage

The exchange sets a minimum price (`ad_slot.bid_floor`). If our campaign's bid floor is below it, we'd lose the auction anyway — wasted compute. ScoringStage filters these out before ranking so we never waste a ranking slot on an unwinnable candidate.

### RankingStage is separate from ScoringStage

Scoring computes numbers. Ranking picks the winner. Separate stages because in Phase 6.5 (ML scoring), we might swap the scorer but keep the same ranking logic. Also enables future multi-slot ranking (pick top-K winners for multiple ad slots in one request).

### ResponseBuildStage now reads ctx.getWinner()

Previously, ResponseBuildStage picked the first candidate itself. Now it reads the winner set by RankingStage. Clean separation — each stage does one thing.

## Test Results

```
Pipeline: [RequestValidation: 0.008ms, UserEnrichment: 2.890ms, CandidateRetrieval: 0.050ms,
           FrequencyCap: 12.454ms, Scoring: 0.072ms, Ranking: 0.003ms, ResponseBuild: 0.154ms]
           total=16.049ms deadline=50ms bid=true
```

| Test | Result |
|------|--------|
| user_00042, floor $0.30 | HealthPlus camp-008, $0.60 (2/3 overlap, highest score) |
| Exchange floor $5.00 | 204 NO_MATCHING_CAMPAIGN (all campaigns filtered) |
| Exchange floor $0.20 | HealthPlus camp-008 (same winner, all qualify) |

## How to test

```bash
mvnw.cmd clean package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Best score wins (HealthPlus > Nike for this user)
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30}]}"

# Exchange floor too high — all campaigns filtered → 204
curl -v -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":5.00}]}"
```
