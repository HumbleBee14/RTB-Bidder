# Phase 15: ML-Driven Quality Throttling + TODO Cleanup

## What was built

Two things:

1. **`QualityThrottledBudgetPacer`** — a decorator that reads the pCTR score from the ML scorer and throttles low-quality bids, saving budget for high-quality opportunities. Opt-in via config. Default behavior (no throttling) is unchanged. Completes the "ML-driven throttling" item parked in Phase 7's future enhancements.

2. **TODO cleanup** — a sweep of all stale/pending TODOs across the codebase. Resolved issues fixed, design decisions documented in their phase, stale TODOs removed. See the [TODO cleanup](#todo-cleanup) section below.

## Why this matters

A constant-rate pacer spends evenly regardless of request quality. But not all requests are equal:
- A user with pCTR 0.20 (20% click probability) is **10x more valuable** than one with pCTR 0.02
- Spending $1 on the low-quality user is wasteful if we could save it for the high-quality one

Quality throttling fixes this:
- **High pCTR** → always bid (full spend, capture valuable clicks)
- **Low pCTR** → skip entirely (save budget)
- **Middle pCTR** → probabilistic bid, linearly interpolated

Result: same daily budget, higher average CTR, better ROI per dollar spent.

## How the throttling formula works

```
score >= highThreshold        → 100% spend (high-quality, bid full)
score <  lowThreshold         →   0% spend (low-quality, skip)
score in between              → linearly interpolated probability
```

With defaults `low=0.05`, `high=0.20`:

| pCTR score | Spend probability | Behavior |
|-----------|-------------------|----------|
| 0.01 | 0% | Always skip |
| 0.05 | 0% | Edge — skip |
| 0.10 | 33% | Probabilistic |
| 0.15 | 67% | Probabilistic |
| 0.20 | 100% | Always bid |
| 0.50 | 100% | Always bid (capped) |

The linear interpolation avoids a hard cliff — a request at 0.049 and 0.051 shouldn't behave completely differently.

## Architecture — decorator pattern again

This is the fifth time we've used the decorator pattern for budget pacing:

```java
LocalBudgetPacer (or DistributedBudgetPacer)
  └─> HourlyPacedBudgetPacer     (optional — hourly smoothing)
       └─> QualityThrottledBudgetPacer  (optional — pCTR gating)
```

Each layer has one job:
- Inner pacer: track budget, decrement atomically
- Hourly pacer: enforce per-hour budget caps
- Quality pacer: decide whether to attempt the spend at all

Zero changes to `BidPipeline` or `BudgetPacingStage`. The stage calls `trySpend(id, amount, score)` — the default implementation on `BudgetPacer` ignores the score (backward compatible), the quality pacer overrides it.

## How pCTR flows to the pacer

```
Request → Scoring stage (MLScorer sets AdCandidate.score = pCTR × value)
       → Ranking stage (sorts by score, picks top per slot)
       → BudgetPacing stage (calls pacer.trySpend(id, price, winner.score))
          └─> QualityThrottledBudgetPacer reads the score, decides spend/skip
```

No new data plumbing needed — the score was already on `AdCandidate`. Phase 15 is a pure extension: one new class, one added overload on the interface, one line changed in `BudgetPacingStage`.

## Pacer coordination — what the Phase 7 table predicted

Phase 7 documented this as requiring "Scorer + Pacer coordination, TODO in code". The coordination is minimal:
- Scorer writes score → Ranking passes score-sorted candidates → Pacer reads the score

The "coordination" is just: the pacer sees the same score the scorer produced. No new IPC, no shared state, no event bus. The decorator pattern keeps coupling at the interface level.

## When to enable this

Enable when:
- You're running `SCORING_TYPE=ml` or `cascade` (pCTR scores are meaningful)
- Campaign budgets are small relative to request volume (throttling = savings)
- You care about CTR/conversion rate, not just fill rate

Don't enable when:
- You're running `SCORING_TYPE=feature-weighted` (scores aren't real pCTR, the thresholds won't make sense)
- Campaigns have abundant budget (nothing to save)
- You want to maximize fill rate above all else

## Files

| File | Change |
|------|--------|
| `pacing/BudgetPacer.java` | Added default `trySpend(id, amount, score)` method — backward compat |
| `pacing/QualityThrottledBudgetPacer.java` | NEW — decorator that gates spends by score |
| `pipeline/stages/BudgetPacingStage.java` | Passes winner's score to `trySpend` |
| `Application.java` | Wires quality throttling when `pacing.quality.throttling.enabled=true` |
| `application.properties`, `.env.example` | New config keys |

## Configuration

```properties
# Default: off (no throttling, original behavior preserved)
pacing.quality.throttling.enabled=false

# Enable — requires SCORING_TYPE=ml or cascade for meaningful thresholds
pacing.quality.throttling.enabled=true
pacing.quality.threshold.low=0.05
pacing.quality.threshold.high=0.20
```

Tune the thresholds to your scorer's output distribution. If most scores fall between 0-1 (like a raw pCTR), `low=0.05, high=0.20` is a sensible start. If scores are pCTR × value_per_click (can be much larger), adjust proportionally.

## How to run (with throttling enabled)

```bash
# 1. Start Redis
docker compose up -d redis
bash docker/init-redis.sh | docker exec -i $(docker ps -qf name=redis) redis-cli --pipe

# 2. Start bidder with ML scorer + quality throttling
SCORING_TYPE=ml \
  PACING_QUALITY_THROTTLING_ENABLED=true \
  PACING_QUALITY_THRESHOLD_LOW=0.05 \
  PACING_QUALITY_THRESHOLD_HIGH=0.20 \
  java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar
```

Expected log output:
```
Scoring type: ml
Pacing type: local
Using local budget pacer (AtomicLong)
Quality-throttled pacing enabled: low=0.05, high=0.20
Quality throttling enabled: low=0.05, high=0.2
```

## Observing throttling behavior

```bash
# Send 100 requests
for i in $(seq 1 100); do curl -s -o /dev/null -X POST http://localhost:8080/bid ... ; done

# Check the pacer's internal counters (via logs at DEBUG level)
# high_quality_spend_count: spends that skipped throttling (score >= high)
# probabilistic_spend_count: spends that rolled the dice and won (score in middle)
# throttled_count: spends blocked by throttling (score < low, or rolled the dice and lost)
```

## Trade-offs and caveats

**Throttling reduces fill rate.** That's the point — you're trading volume for quality. Fill rate drops in exchange for higher average CTR.

**Thresholds need to match your scorer.** The feature-weighted scorer produces scores in a different range than the ML scorer. Using ML thresholds (0.05/0.20) with feature-weighted output will over-throttle (everything skipped) or under-throttle (nothing skipped).

**No observation window yet.** The current pacer makes threshold decisions in isolation — it doesn't know "average quality in the last hour was 0.15, so 0.10 is actually decent right now". A future enhancement could adaptively recalibrate thresholds based on observed score distribution.

**Probabilistic skipping adds variance.** On a small number of requests, luck-of-the-roll can make fill rate noisy. Averages out at scale.

## Future enhancements

Candidate next steps if this feature proves valuable:
- Per-campaign thresholds (some campaigns need higher quality than others)
- Time-of-day adaptive thresholds (peak hours get stricter, off-peak gets looser)
- Revenue-weighted throttling (factor in expected CPC/CPM, not just pCTR)
- Integration with pacing metrics — expose throttle rate as a Prometheus counter for Grafana

---

## TODO cleanup

A sweep of every `TODO` / `FIXME` in `src/` as part of this phase. Each was either resolved, moved to its relevant phase doc, or deleted as stale.

### 1. `HourlyPacedBudgetPacer` — "ML-driven throttling" TODO → **Resolved by Phase 15**

```java
// BEFORE:
// TODO: ML-driven throttling — adjust bid probability based on predicted conversion value
// AFTER:
// Note: ML-driven quality throttling is implemented in QualityThrottledBudgetPacer (Phase 15),
// which wraps this pacer as an outer decorator when pacing.quality.throttling.enabled=true.
```

### 2. `TrackingHandler` — "look up userId, campaignId, slotId from bid cache" → **Fixed**

The impression and click tracking endpoints were publishing events with `null` for `userId`, `campaignId`, `slotId`. That made CTR-by-campaign queries in ClickHouse impossible.

Fix: `ResponseBuildStage` now embeds those IDs in the tracking URLs as query params. `TrackingHandler` reads them back. Zero extra lookups on the tracking hot path — standard OpenRTB pattern.

See the expanded writeup in [phase-8-kafka-events.md](./phase-8-kafka-events.md) under "Later enhancement: tracking URLs carry user/campaign/slot context".

### 3. `FeatureWeightedScorer` — "use actual remaining budget from BudgetPacer" → **Stale (design evolved)**

When Phase 6 was first written, the plan was for the scorer to read remaining budget and fold it into the score as a "pacing factor". By Phase 7, the design had evolved: pacing is a separate pipeline stage (`BudgetPacingStage`) with decorator-based enforcement (hourly, quality throttling). The scorer producing a constant 1.0 for pacingFactor is the correct final design — pacing decisions happen at spend time, not score time.

Replaced the stale TODO with a comment explaining the design.

### 4. `EmbeddingTargetingEngine` — "replace with ONNX-based sentence-transformers" → **Moved to phase-4.5 docs**

This wasn't a gap — it was a deliberate design choice (averaged word embeddings = simpler, faster, zero-allocation). Converted the in-code TODO to a proper comment, and moved the "when to upgrade to real sentence transformers" discussion to [phase-4.5-embedding-targeting.md](./phase-4.5-embedding-targeting.md) under "Future enhancements".

### Summary

| TODO | File | Resolution |
|------|------|------------|
| ML-driven throttling | `HourlyPacedBudgetPacer.java` | ✅ Implemented in Phase 15 |
| Bid cache lookup (×2) | `TrackingHandler.java` | ✅ Fixed via URL-embedded query params |
| Use remaining budget in scoring | `FeatureWeightedScorer.java` | ✅ Stale — design evolved, now documented |
| ONNX sentence transformers | `EmbeddingTargetingEngine.java` | ✅ Moved to phase-4.5 "Future enhancements" |

All in-code `TODO`s are now zero. Future work items live in their phase docs where they belong.
