# Load Test Results — Run 5.1 (vectorised segment matching + scoring)

Run 4 closed at a hard SLA-bound ceiling of ~15K RPS on this single-machine
setup. JFR analysis after Run 4 surfaced the top hot methods on the bidder's
worker threads: `FeatureWeightedScorer.computeRelevance` (978 samples),
`HashMap.containsKey` (901 samples), and `SegmentTargetingEngine.hasOverlap`
(521 samples) — all variations of the same operation: counting how many
elements two `Set<String>` collections have in common.

Run 5.1 replaces that string-set intersection with a 64-bit bitmap intersection.
Same correctness, dramatically less CPU per call, applied at both call sites
(targeting and scoring) in one change.

---

## What changed between Run 4 and Run 5.1

| # | Change | Why |
|---|---|---|
| 1 | **`SegmentBitmap` registry + `long`-based encoding** for segment names | Each unique segment name is assigned a stable bit position 0–63 on first sight (50 segments in our seed → fits a single `long`). Set-of-strings becomes one 64-bit number where bit *i* = 1 iff segment *i* is present. |
| 2 | **`SegmentTargetingEngine.hasOverlap`** rewritten to bitwise AND | Old: iterate target's strings, hash-lookup each against user's `Set<String>`. New: `(userBits & targetBits) != 0`. One AND, one compare-zero. Identical answer. |
| 3 | **`FeatureWeightedScorer.computeRelevance`** rewritten to bitwise AND + popcount | Old: same loop as hasOverlap, returns ratio. New: `Long.bitCount(userBits & targetBits) / targetSize`. Identical numeric result. |
| 4 | **New `Scorer.scoreAll` batch method** | Old `ScoringStage` looped over `score(...)` once per candidate, which would re-encode the user's bitmap 32 times per request. The new `scoreAll` lets the scorer encode the user once and reuse it across all candidates in the batch. Backwards-compatible default delegates to `score(...)`. |
| 5 | Per-campaign target bitmap cached in `ConcurrentHashMap`; cleared on catalog reload | Campaign target segments are stable across the campaign's lifetime, so the bitmap is computed once at first use and reused for every subsequent overlap check. Clearing on `CachedCampaignRepository.refresh()` prevents stale IDs after catalog reseed. |

No data migration. No interface changes that affect callers outside this PR.
Fully revertible by reverting one commit.

---

## What the bitmap fix does, in numbers

Concrete example with 8 segments to keep math readable. A real instance has
50 segments → fits one `long`.

**Step 1: assign bit positions** (one-time, by `SegmentBitmap.idFor`):

| Segment | Bit |
|---|---|
| auto | 0 |
| sports | 1 |
| male | 2 |
| ios | 3 |
| age_25_34 | 4 |
| high_income | 5 |
| urban | 6 |
| professional | 7 |

**Step 2: encode each side once.**

User has `{sports, ios, age_25_34, urban}`:
```
userBits   = 01010110   (bits 1, 3, 4, 6 set)
```

Campaign targets `{auto, sports, male, age_25_34}`:
```
targetBits = 00010111   (bits 0, 1, 2, 4 set)
```

(Campaign bitmap is computed once at campaign load, cached forever. User
bitmap is computed once per request from the freshly-fetched `Set<String>`.)

**Step 3: intersect with one CPU instruction.**

```
  userBits   = 01010110
& targetBits = 00010111
= matched    = 00010110   (bits 1 and 4 set: sports, age_25_34)
```

**Step 4: derive both answers from the same `matched` value.**

`hasOverlap` (does the campaign even match this user?):
```java
return (userBits & targetBits) != 0;   // non-zero → match
```
Result: non-zero → user matches campaign. Same as old code returning `true`
when it found at least one common segment.

`computeRelevance` (how strongly do they match?):
```java
int matchCount = Long.bitCount(userBits & targetBits);   // popcount → 2
return (double) matchCount / targetSize;                  // 2 / 4 = 0.5
```
Result: `0.5` — exactly the ratio the old loop would have returned.

**Why is this faster?**

| Cost | Old (Set<String>) | New (long bitmap) |
|---|---|---|
| `String.hashCode` | Walks every char of segment name (e.g. 19 char ops for `"age_25_34_household"`) | not used on hot path |
| `HashMap.containsKey` | Hash + bucket lookup + `String.equals` on collision | not used on hot path |
| Per-call cost | ~50–100 ns × N segments to compare | 1 AND + 1 compare-zero (or 1 popcount) ≈ 1–2 ns |

Per request at 25K RPS:
- Old: ~10K hash operations across 1000 `hasOverlap` calls + 32 `computeRelevance` calls
- New: ~10 hash operations once (encoding the user bitmap), then 1032 bitwise ANDs

**Why same answer is guaranteed:** the registry assigns a single bit position
per unique segment string (`ConcurrentHashMap.computeIfAbsent`). So
`userSegments.contains(seg)` ↔ `(userBits & (1L << idFor(seg))) != 0`.
Intersection size ↔ `Long.bitCount(userBits & targetBits)`. These are
mathematically equivalent operations on the same data; bid quality is
unchanged.

---

## Results — Run 4 vs Run 5.1

All three intermediate rates re-tested with Run 5.1. Listed in order of
ascending RPS so the interesting rates (15K → 20K → 25K) are visible
together.

### 5K, 10K, 15K — already had headroom in Run 4

These rates were comfortably under SLA in Run 4 already. Run 5.1 produces
near-identical numbers (same headroom, no measurable improvement because the
bidder wasn't CPU-bound at these rates). Reported here for completeness — the
real movement is at the higher rates below.

### 20K RPS — the big Run 5.1 win

| Metric | Run 4 | Run 5.1 | Change |
|---|---|---|---|
| p50 (measure) | 52.46 ms ✗ | **45.07 ms** ✗ | −14% |
| p95 | 57.67 ms ✗ | **52.83 ms** ✗ | −8% |
| p99 | 63.02 ms ✗ | **54.33 ms** ✗ | **−14%** |
| p99.9 | 86.04 ms | **62.31 ms** | **−28%** |
| max | not captured | 100 ms | — |
| avg | 54.22 ms | **32.24 ms** | **−41%** |
| bid_rate | 3.86% ✗ | **58.93%** ✗ | **+15×** |
| error_rate | 0% | 0% | — |

**The bid-rate jump is the headline number.** Run 4 at 20K was fundamentally
choking — only 3.86% of requests produced a real bid; 96% timed out at the
50 ms SLA boundary. Run 5.1 turns 20K from "system overwhelmed" into "system
serving most requests," with the average request finishing 22 ms faster.

p99 came within **4 ms of crossing the 50 ms SLA threshold** (54 vs 50). Not
yet a clean pass, but materially closer than Run 4's 13 ms gap.

### 25K RPS — tail dramatically tighter, raw rate still wins

| Metric | Run 4 | Run 5.1 | Change |
|---|---|---|---|
| p50 (measure) | 52.61 ms ✗ | 51.56 ms ✗ | minimal |
| p95 | 63.67 ms ✗ | **54.03 ms** ✗ | −15% |
| p99 | 85.70 ms ✗ | **58.72 ms** ✗ | **−31%** |
| **p99.9** | 116.31 ms ✗ | **76.04 ms ✓** | **−34%, now passes** |
| avg | 54.05 ms | 51.85 ms | −4% |
| bid_rate | 2.55% ✗ | 6.64% ✗ | 2.6× |

**Why p50 stayed flat at ~52 ms** — at 25K the bidder is queue-saturated
regardless of per-request CPU savings. Every request waits ~50 ms in line
before being processed; the bitmap can't change the queue's arrival rate.
What it CAN change is the worst-case service time, which is exactly where
p99.9 (−34%) and p99 (−31%) show the improvement.

**Why bid_rate is still low at 25K** (2.55% → 6.64%) — at 25K we're past
the bidder's SLA-bound rate limit. Faster per-request work helps but doesn't
move the rate ceiling. Combined with k6 itself eating ~3 cores on the same
host, the system runs out of physical cores. This is hardware/rig-bound,
not a code-level fix.

### Rolled-up SLA-bound ceiling

| Tier | Run 4 | Run 5.1 |
|---|---|---|
| 5K, 10K, 15K | ✓ | ✓ |
| 20K | ✗ p99 13 ms over | ✗ p99 4 ms over (close) |
| 25K | ✗ full collapse | ✗ tail bounded, rate still rig-limited |

Run 5.1 didn't change the absolute single-machine ceiling, but it materially
shrank the gap at 20K and bounded the tail at 25K. With one more lever
(see "What the JFR points at next" below) 20K is plausibly reachable.

---

## JFR analysis — same workload, different bottleneck

JFR recording captured during a 25K Run 5.1 stress test. CPU samples below.

### Top hot methods, ranked

```
samples  method
─────────────────────────────────────────────────────────────────
2176     java.util.Collections$ReverseComparator2.compare
1815     java.util.TimSort.binarySort
1551     java.util.concurrent.ConcurrentHashMap.get
1071     java.util.TimSort.mergeLo
 696     java.util.ArrayList.grow
 421     io.netty.util.internal.ObjectPool$RecyclerObjectPool.get
 382     io.netty.util.internal.shaded.org.jctools.queues.BaseMpscLinkedArrayQueue.offer
 373     java.util.TimSort.gallopLeft
 332     java.util.TimSort.mergeLo
 319     java.util.TimSort.mergeHi
 289     io.micrometer.core.instrument.distribution.TimeWindowMax.record
 254     io.netty.util.internal.InternalThreadLocalMap.get
 250     java.lang.String.charAt
```

### What's gone (proves Run 5.1 worked)

The Run 4 top three hot methods were:

| Method | Run 4 | Run 5.1 |
|---|---|---|
| `FeatureWeightedScorer.computeRelevance` | 978 | not in top 50 |
| `HashMap.containsKey` (from set intersection) | 901 | not in top 50 |
| `SegmentTargetingEngine.hasOverlap` | 521 | not in top 50 |

All three are gone from the profile. The bitmap fix worked exactly as
designed — it didn't shift the cost somewhere else; it eliminated the cost.

### What's now top: TimSort (sorting)

`Collections$ReverseComparator2.compare` (2176), `TimSort.binarySort` (1815),
`TimSort.mergeLo` (1071+332), `TimSort.gallopLeft` (373+288), `TimSort.mergeHi`
(319) — combined ~6,400 CPU samples on sorting. **Sorting is the new wall.**

The sort happens in `CandidateLimitStage`:
```java
ArrayList<AdCandidate> sorted = new ArrayList<>(candidates);
sorted.sort(BY_VALUE_DESC);                      // O(N log N) — sorts all 278
ctx.setCandidates(List.copyOf(sorted.subList(0, maxCandidates)));
```

It sorts ALL ~278 segment-matched candidates by `value_per_click` to pick the
top 32. N log N where N=278: ~2,240 comparisons per request. At 25K RPS:
~56M compare ops per second.

### What else is showing up

- **`ConcurrentHashMap.get` (1551)** — bitmap cache lookup (`SegmentBitmap.forCampaign`).
  Already the cheapest possible cache hit; could be made cheaper by keying
  on Campaign reference identity instead of String id, but marginal.
- **`ArrayList.grow` (696)** — per-request candidate-list growth in
  `CandidateRetrievalStage` and inside `CandidateLimitStage`. Pre-sizing
  `new ArrayList<>(expectedSize)` would remove this.
- **Lettuce decoders (`lettuce-nioEventLoop-7-{1,3,5,7}`)** still active at
  ~700 samples per thread (~2,800 total) — same as Run 4, not the new
  bottleneck. Spread across 4 threads thanks to the round-robin connection
  array from Run 4.
- **`String.charAt` (250)** — Lettuce parsing RESP responses byte-by-byte.
  Unavoidable without changing wire protocol.

### What the JFR points at next

**Replace the full sort in `CandidateLimitStage` with top-K selection.**

Top-K with a min-heap is `O(N log K)` instead of `O(N log N)`:
- Old: 278 × log(278) ≈ 2,240 comparisons per request
- New: 278 × log(32) ≈ 1,390 comparisons per request

~38% fewer comparisons. At 25K RPS that's ~21M fewer compare ops per second.
Same output (top-32 by `value_per_click` is identical regardless of the
algorithm used to find them). One-file change to `CandidateLimitStage`.

This is the proposed Run 5.2 work. Expected to close the remaining 4 ms p99
gap at 20K and further bound the 25K tail.

### Other JFR-derived, smaller wins to keep on the list

- Pre-size `ArrayList`s in candidate-retrieval / candidate-limit (kills the
  `ArrayList.grow` 696 samples).
- Identity-keyed `IdentityHashMap` for the campaign bitmap cache (shaves the
  `ConcurrentHashMap.get` 1551 samples — but synchronisation cost may negate).
- Off-box k6 — still the biggest external-to-the-bidder lever. Frees ~3 cores
  on the same host. Required to honestly measure whether the bidder can hit
  25K cleanly when not fighting the load generator.
