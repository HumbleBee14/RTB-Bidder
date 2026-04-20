# Phase 7: Budget Pacing — Don't Overspend

## What was built

Atomic budget pacing with two implementations: local (AtomicLong for single-instance) and distributed (Redis DECRBY for multi-instance). BudgetPacingStage validates the winner's budget after ranking — if exhausted, falls back to the next eligible candidate.

This completes the core pipeline — all 8 stages from the plan are now in place.

## Request Flow (Phase 7 — Full Pipeline)

```
  ┌─ BidPipeline (8 stages) ────────────────────────────────────────┐
  │                                                                 │
  │  1. RequestValidationStage (shared)                             │
  │  2. UserEnrichmentStage (shared) ◄── Redis SMEMBERS             │
  │  3. CandidateRetrievalStage (shared) ◄── SegmentTargetingEngine │
  │  4. FrequencyCapStage (shared) ◄── Redis GET                    │
  │  5. ScoringStage (shared) ◄── Scorer interface                  │
  │  6. RankingStage (per-slot) — size match, floor, dedup          │
  │  7. BudgetPacingStage (per-slot) ◄── BudgetPacer                │
  │       │                                                         │
  │       │  trySpend(camp-008, $0.60) → true (budget: $5999.40)    │
  │       │  trySpend(camp-006, $0.40) → true (budget: $1499.60)    │
  │       │                                                         │
  │       │  If budget exhausted → fallback to next candidate       │
  │       │  If ALL exhausted → abort(BUDGET_EXHAUSTED)             │
  │       ▼                                                         │
  │  8. ResponseBuildStage (per-slot)                               │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘
```

## Two Implementations

| Implementation | Storage | Use case | Thread safety |
|---------------|---------|----------|--------------|
| `LocalBudgetPacer` | `ConcurrentHashMap<String, AtomicLong>` | Single bidder instance | Lock-free atomic ops |
| `DistributedBudgetPacer` | Redis `DECRBY` via Lua script | Multiple bidder instances behind LB | Redis single-threaded guarantees |

### Why microdollars?

Budgets are stored as `long` microdollars ($1.00 = 1,000,000 microdollars) instead of `double`. `AtomicLong` doesn't support floating-point, and `double` arithmetic has precision issues at scale. $5000.00 budget after 10,000 deductions of $0.50 should be exactly $0.00, not $0.000000001.

### Why atomic decrement with rollback?

```java
long remaining = budget.addAndGet(-amountMicros);
if (remaining < 0) {
    budget.addAndGet(amountMicros);  // rollback
    return false;
}
```

Two bidder threads spending the last $1 simultaneously: both call `addAndGet(-1)`. One gets 0 (success), the other gets -1 (overspent → rollback). No locks, no CAS loops, O(1).

### Why Lua script for distributed?

```lua
local remaining = redis.call('DECRBY', KEYS[1], ARGV[1])
if remaining < 0 then
  redis.call('INCRBY', KEYS[1], ARGV[1])
  return -1
end
return remaining
```

Same atomic decrement + rollback pattern, but in Redis. The Lua script runs atomically — no other command can execute between DECRBY and the check. Multiple bidder instances competing for the same budget are serialized by Redis.

## Fallback on budget exhaustion

BudgetPacingStage doesn't just reject the winner — it tries the next best candidate:

```
RankingStage picked: HealthPlus (score 0.40) for slot-top
BudgetPacing: trySpend(HealthPlus, $0.60) → false (budget exhausted!)
  → fallback: Nike (score 0.25, next best for 728x90)
  → trySpend(Nike, $0.75) → true (budget available)
  → slot-top winner changed to Nike
```

If ALL candidates for a slot are exhausted → that slot gets no bid. If ALL slots have no winners → abort(BUDGET_EXHAUSTED).

## Files

| File | Purpose |
|------|---------|
| `pacing/BudgetPacer.java` | Interface — `trySpend(campaignId, amount)` + `remainingBudget()` |
| `pacing/LocalBudgetPacer.java` | AtomicLong, microdollars, lock-free |
| `pacing/DistributedBudgetPacer.java` | Redis DECRBY via Lua script |
| `pipeline/stages/BudgetPacingStage.java` | Validates winner budget, fallback to next candidate |

## Test Results

```
Pipeline: [RequestValidation: 0.009ms, UserEnrichment: 2.687ms, CandidateRetrieval: 0.079ms,
           FrequencyCap: 10.843ms, Scoring(FeatureWeightedScorer): 0.051ms, Ranking: 0.040ms,
           BudgetPacing: 0.030ms, ResponseBuild: 0.155ms]
           total=14.444ms deadline=50ms bid=true
```

BudgetPacing adds 0.03ms (pure in-memory AtomicLong). Negligible in the 50ms SLA budget.

| Test | Result |
|------|--------|
| Normal bid | 200 — budget deducted, winner confirmed |
| Multi-slot | 200 — budget deducted per slot winner |
| Unknown user | 204 NO_MATCHING_CAMPAIGN |
| 8 stages in pipeline log | All visible with timing |

## How to test

```bash
mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Normal bid — budget deducted
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30}]}"

# Watch logs for BudgetPacing stage timing and "Budget exhausted" messages
```
