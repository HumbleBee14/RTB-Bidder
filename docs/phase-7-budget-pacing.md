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
| `pacing/LocalBudgetPacer.java` | CAS loop on AtomicLong, microdollars, lock-free |
| `pacing/DistributedBudgetPacer.java` | Redis DECRBY via Lua script, SETNX seeding |
| `pacing/HourlyPacedBudgetPacer.java` | Decorator — hourly rate limiting + spend smoothing |
| `pacing/BudgetMetrics.java` | Spend/exhaustion/throttle counters (pre-Phase 9) |
| `pipeline/stages/BudgetPacingStage.java` | Validates winner budget, loops ALL fallback candidates |

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

## Implementation Details

### CAS loop vs rollback pattern

The original implementation used `addAndGet(-amount)` + check + `addAndGet(+amount)` rollback. This has a race window: two threads can both overshoot, both detect negative, both roll back — budget ends at original value, neither spends. The CAS (Compare-And-Set) loop eliminates this:

```java
do {
    current = budget.get();
    next = current - amountMicros;
    if (next < 0) return false;      // check BEFORE decrementing
} while (!budget.compareAndSet(current, next));  // atomic swap
```

Only one thread can win the CAS. Losers retry with the updated value. Zero race window.

### Microdollar conversion: Math.round, not cast

`(long)(0.3 * 1_000_000)` = `299999` (truncation due to IEEE 754 double representation). `Math.round(0.3 * 1_000_000)` = `300000` (correct). At 50K QPS, truncation under-deducts ~$0.01 per second = $864/day lost tracking.

### Config-driven pacer selection

```properties
pacing.type=local         # single instance (AtomicLong)
pacing.type=distributed   # multi-instance (Redis DECRBY)
```

### Exhaustion fallback: iterates ALL candidates, not just one

When the winner's budget is exhausted, BudgetPacingStage loops through all remaining candidates by score until one succeeds or all are exhausted. No money left on the table.

## Hourly Pacing + Spend Smoothing

### Why hourly pacing is critical

Without hourly pacing, a $1000/day campaign running during a 50K QPS morning traffic spike will burn its entire budget in minutes. The afternoon has zero budget left — the campaign is invisible for 20+ hours. Advertisers hate this.

### How it works

`HourlyPacedBudgetPacer` wraps any `BudgetPacer` (decorator pattern):

```
hourlyBudget = remainingTotalBudget / hoursRemainingInDay

Example at 2pm (10 hours remaining):
  Campaign budget: $500 remaining
  Hourly budget: $500 / 10 = $50/hour
  
At 6pm (6 hours remaining), if only $200 was spent by 6pm:
  Remaining: $800
  Hourly budget: $800 / 6 = $133/hour (unspent hours roll forward)
```

Dynamic recalculation means quiet hours don't waste budget — it rolls into peak hours.

### Spend smoothing (gradual throttle, not hard cutoff)

A hard cutoff at 100% hourly budget creates a spike-then-silence pattern. Spend smoothing ramps down gradually:

```
Hourly utilization    Bid probability
< 80%                 100% (always bid)
80-95%                Linear ramp down (80% → 100%, 95% → 0%)
> 95%                 0% (stop, leave 5% buffer for race conditions)
```

This produces smooth delivery instead of a sawtooth pattern.

### Config

```properties
pacing.hourly.enabled=true    # wrap base pacer with hourly pacing
pacing.hourly.hours=24        # spread across 24 hours
```

### Performance

```
BudgetPacing (with hourly pacing): 0.07ms   (vs 0.03ms without)
```

The hourly layer adds ~0.04ms — one `remainingBudget()` call + hourly utilization calculation + ThreadLocalRandom check. Negligible in the 50ms SLA.

## What production systems do beyond total budget

Our implementation enforces total campaign budget. Production systems add:

| Feature | What it does | Why |
|---------|-------------|-----|
| **Hourly pacing** | Spend budget evenly across 24 hours | Prevents burning entire budget in morning traffic spike |
| **Spend smoothing** | Gradual delivery curve, not step function | Avoids throttling during live events |
| **ML-driven throttling** | Adjust bid rate based on conversion likelihood | Spend more when high-conversion users are active |
| **Cross-region coordination** | Synchronized pacing service across data centers | Multiple bidder regions share the same budget |

These are future enhancements. Our atomic decrement + fallback is the foundation they build on.

References:
- [Budget Pacing in RTB](http://www0.cs.ucl.ac.uk/staff/w.zhang/rtb-papers/opt-rtb-pacing.pdf)
- [Ad Banker Case Study](https://clearcode.cc/portfolio/ad-banker-case-study/)
- [RTB Data & Revenue Architecture](https://e-mindset.space/blog/ads-platform-part-3-data-revenue/)

## How to test

```bash
mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Normal bid — budget deducted
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30}]}"

# Watch logs for BudgetPacing stage timing and "Budget exhausted" messages
```
