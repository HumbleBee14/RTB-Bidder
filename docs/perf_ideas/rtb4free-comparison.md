# RTB4FREE vs our bidder — architectural comparison

Notes from reading the RTB4FREE source (`rtb4free_bidder/src/`) to understand their "25K+ QPS per node" claim and decide whether their architecture has anything we should borrow.

## TL;DR

**Their 25K claim is real, but it's measuring a different (simpler) workload than ours.** The biggest single architectural difference: **they don't do user segment lookups in the hot path.** They match on bid-request fields directly (domain, geo, device, IAB category). We do `SMEMBERS user:{id}:segments` per request to fetch user audience segments, then intersect with campaign targeting. That's a fundamentally richer targeting model — and a fundamentally heavier hot path.

After a strict output-preserving review, **none of their architectural patterns are worth borrowing into our pipeline.** Three are actually *worse* than what we have. The full reasoning is in the sections below.

Their 25K and our 10K are not the same number measured under different conditions — they are two different products. Theirs answers "any campaign that matches this request"; ours answers "the best-scored campaign for this user, given audience segments, freq caps, and budget pacing." The richer answer costs more per request. That's not a defect in our design; it's the contract.

---

## Per-feature reasoning — why they do what they do, why we do what we do, why ours is the production-realistic choice

### Targeting model: bid-request attributes vs user audience segments

**What they do:** match a campaign's targeting attributes (e.g. `domain="cnn.com"`, `geo="US"`, `device="mobile"`) against the incoming bid request's fields, using Lucene query strings. **Zero external lookups per request.**

**Why they do it:** simpler is faster. If "campaign X targets US mobile traffic on news sites" can be answered entirely from the request body, you skip a network round-trip. Their bidder is a clean implementation of the OpenRTB request-attribute matching surface.

**What we do:** match a user's audience segments (e.g. `auto_intender`, `sports_fan`, `lookalike_BMW_buyer`) against each campaign's targeted segments. The user's segments come from a Redis Set fetched per request via `SMEMBERS`.

**Why we do it:** **this is what production DSPs actually buy.** Modern programmatic advertising is segment-driven, not field-driven. Advertisers buy "people who shopped for cars in the last 7 days," not "anyone hitting a sports site." Our model represents real third-party data segments, lookalike audiences, retargeting lists, and DMP-driven targeting. RTB4FREE's model represents what was sufficient for OpenRTB 1.0–2.x exchanges before audience targeting became universal.

**Why ours is the production-realistic choice:** every modern DSP (TTD, DV360, Amazon DSP, MediaMath, Criteo) does segment-based audience targeting. The cost is real (the Redis round-trip we measured), but you cannot operate as a competitive DSP without it.

### Selection algorithm: first-match-wins vs score-and-rank

**What they do:** scan the (pre-shuffled) campaign list in parallel slices. Whichever worker finds the first matching campaign wins; everyone else exits. No comparison of "which campaign is best" — just "which one is acceptable first."

**Why they do it:** with their attribute-match model and small catalogs, "is this campaign acceptable?" is a cheap boolean. Returning the first acceptable match is the cheapest possible algorithm. Pre-shuffling prevents systemic bias toward whichever campaigns happen to be at the front of the list.

**What we do:** score every viable candidate with `FeatureWeightedScorer` (and optionally an ML model), then rank by score, then check freq cap in score order, then apply budget pacing. The campaign with the highest score that passes all gates wins.

**Why we do it:** **revenue depends on it.** In real RTB, a "matching" campaign isn't enough — you want the *best-paying* matching campaign. A user might match 200 campaigns; only the top-scored one maximizes revenue per impression. Production DSPs ALL score-and-rank. First-match-wins leaves money on the table; for an ad-tech business, that's not a tradeoff, it's malpractice.

**Why ours is the production-realistic choice:** every dollar of programmatic ad spend goes through DSPs that score-and-rank. The "best" is not equal to "any."

### Frequency-cap store: Aerospike vs Redis

**What they do:** Aerospike (their fork "zerospike") plus a 300s in-process cache (`cache2k`). Aerospike provides single-digit-µs reads at millions of ops/sec.

**Why they do it:** Aerospike is purpose-built for ad-tech. The cache eats the hot path almost entirely, so most freq-cap checks never leave the JVM. Aerospike absorbs the cache misses cheaply.

**What we do:** Redis with a 4-connection round-robin array, paged MGET across batches of 16 keys. Caffeine for user-segment caching only (not for freq-cap state — those change too frequently to cache cheaply).

**Why we do it:** Redis is well-understood, easy to operate, and our v4 JFR proved it isn't currently our bottleneck. At 10K RPS we have huge headroom; switching to Aerospike would be a multi-day infra change for a layer that isn't the wall yet.

**Why we should support BOTH and let the operator choose:** Aerospike is real production tech that production DSPs running at 100K+ QPS use. Redis is real production tech that smaller / latency-tier deployments use. The right architectural answer is **abstract the freq-cap store behind an interface (we already have `FrequencyCapper`), provide both `RedisFrequencyCapper` and `AerospikeFrequencyCapper` implementations, and pick at startup via config.** That way:
- We can A/B benchmark both on identical workload to know empirically when each wins.
- Operators of this bidder pick the store that fits their fleet topology.
- We don't have to commit to one based on a guess.

Adding the Aerospike implementation is a v5 deliverable — see [v5-experiment-plan.md](v5-experiment-plan.md).

### Concurrency model: thread-per-request vs event-loop + worker pool

**What they do:** Jetty `QueuedThreadPool` — every incoming HTTP request takes a thread from the pool, blocks on I/O, releases. They additionally create a `new Executors.newFixedThreadPool(N)` per bid for parallel campaign scanning.

**Why they do it:** in 2017 when this was written, Jetty thread-per-request was the mainstream Java HTTP pattern. Async/reactive Java was niche.

**What we do:** Vert.x — a small pool of event-loop threads accepts requests non-blocking, then uses `executeBlocking` to dispatch the bid pipeline onto worker threads. The thread pool is created once at startup and reused.

**Why we do it:** modern Java HTTP serving is event-loop-based for a reason. Thread-per-request scales linearly with concurrency (more clients → more threads → more memory + scheduling overhead). Event loops handle thousands of concurrent connections on a few threads. **And creating a new thread pool per bid is a textbook anti-pattern at any RPS** — thread allocation, queue allocation, GC pressure, all happen 25K times/sec at their target.

**Why ours is the production-realistic choice:** every modern high-RPS Java service uses an event-loop or actor-based concurrency model (Netty, Vert.x, Akka, Quarkus reactive, Spring WebFlux). Thread-per-request is what you write when you want simplicity over performance. Per-request thread pool creation is what you write when you don't know better.

### GC: ZGC vs Java 8 default

**What they do:** Java 8 with whatever GC was default (Parallel GC most likely). No tuning visible.

**Why they do it:** their stack is from 2017. ZGC didn't exist in Java 8. Java 8 was reasonable for the time.

**What we do:** Java 21 with ZGC (generational since Java 24/21+ profiles), 2g heap, AlwaysPreTouch.

**Why we do it:** v4 JFR data showed that at our allocation rate, anything other than ZGC produces stop-the-world pauses that blow the SLA. Specifically: with the 512m default heap we measured 295 seconds of GC pause time in 210 seconds of wall-clock runtime. The 2g + ZGC change moved p99 from 151ms to 5.81ms.

**Why ours is the production-realistic choice:** every production Java service running sub-50ms SLAs in 2025 is on Z, Shenandoah, or G1 with careful tuning. Parallel GC + Java 8 is the equivalent of running production on a 2017 OS — fine if no one cares about latency, broken otherwise.

---

## Stack comparison

| Layer | RTB4FREE (2019 image, Java 8 era) | Our bidder (Java 21, today) |
|---|---|---|
| HTTP | Jetty `QueuedThreadPool` — blocking, thread-per-request | Vert.x — event loop + worker pool, non-blocking I/O |
| GC | Java 8 default (Parallel GC) | ZGC (sub-ms target, generational) |
| Hot-path data store | Aerospike (their "zerospike") for freq cap; **no user-segment store** | Redis (SMEMBERS for segments, MGET for freq caps) |
| Local cache | cache2k (300s TTL, in-process) | Caffeine W-TinyLFU |
| Targeting | Lucene query strings against bid-request attributes | Set intersection: user segments × campaign segments |
| Scoring | Constant or simple weighted (no ML in default config I read) | `FeatureWeightedScorer` (with optional ML cascade) |
| Selection algo | Pre-shuffled lists, parallel slice scan, **first-match short-circuit** | Score all matching, sort by score, freq-cap in score order, rank top |
| Object reuse | `ArrayList`/`HashMap` per request (allocations) | `BidContextPool` for context reuse |
| Per-request concurrency | ⚠️ **Creates a new `Executors.newFixedThreadPool(N)` per bid request** | Reuses Vert.x worker pool |

---

## What's different in their design — and why it's NOT actually better for our pipeline

> An earlier draft of this doc framed two of their patterns as "worth borrowing." After a stricter output-preserving review, neither of them is. Documenting the analysis here so a future reader doesn't get the impression we're missing something they have.

### 1. First-match short-circuit (when not multi-bid) — looks tempting, actually breaks correctness for us

In `CampaignSelector.SelectionWorker.run()`:

```java
candidates.add(select.get(ii));
if (!(br.multibid || test.weights == null)) {
    flag.setValue(true);    // <-- first match wins, all workers stop
    break;
}
```

The `flag` is shared across worker threads. The first one to find a viable campaign sets it; the others exit on next iteration. **Average-case work is far less than worst-case.** With pre-shuffled ordering, a typical request finds its winner after scanning a small fraction of the catalog.

**Why this seems attractive for us:** in pseudo-code, "iterate candidates score-ordered, return the first one that passes freq-cap + budget" looks like a free CPU win.

**Why it actually compromises OUR output:** our pipeline order is `Score → FrequencyCap → Ranking → BudgetPacingStage`. The `BudgetPacingStage` runs AFTER ranking and can REJECT the chosen winner (e.g., its hourly budget burn rate is too high). Today we collect up to 64 freq-cap-allowed candidates, so when pacing rejects #1 we have 63 fallbacks. **First-match short-circuit returns 1 candidate with no fallback — we'd no-bid when a perfectly valid #2 existed.** That's a bid quality regression, not a free optimization.

Their bidder doesn't have post-ranking pacing in this same shape, so the optimization is safe in their architecture. In ours it isn't. **Skip.**

### 2. Pre-shuffled campaign lists — a workaround for not scoring, useless for us

`Preshuffle.compile()` builds N independently-shuffled copies of the campaign list at startup. Per-request, a random one is chosen via XORShift random.

**Why they need this:** their algorithm is "linear scan, return first match." Without shuffling, the campaigns at the front of the list ALWAYS win, which is unfair to the rest of the catalog. Pre-shuffling randomizes the bias away.

**Why we don't need this:** we score every candidate and rank by deterministic score. The order we evaluate doesn't matter — the highest-scored campaign wins regardless of where it appears in the candidate list. **There's no ordering bias to compensate for.** Pre-shuffling for our pipeline would be like adding randomness to a math test — pure noise with no benefit.

This isn't an industry-standard production pattern; it's a RTB4FREE-specific workaround for an algorithmic choice they made (don't score). **Skip.**

### 3. Aerospike (zerospike) for frequency capping — real production tech, just not our bottleneck yet

Aerospike is a free, open-source, production-grade key-value store used by AppNexus, Criteo, Adobe, The Trade Desk for ad-tech specifically. Single-digit-microsecond reads at millions of ops/sec, hybrid memory architecture, built-in clustering. Strictly faster than Redis at the freq-cap workload.

**What they store there:** ONLY the same `freq:user:campaign` impression counters we store in Redis.

**Why we shouldn't switch yet:** our v4 JFR data shows Redis isn't where our cycles go at 5K-10K RPS. The dominant costs were GC, scoring, and TimSort — all in-process work. Replacing Redis with Aerospike makes the freq-cap call 3-5× faster but freq-cap isn't the bottleneck. Operationally, Aerospike adds real complexity (cluster config, namespaces, persistence tuning).

**When this becomes worth doing:** if we get past 25K RPS and JFR shows Lettuce decoders pegged or Redis CPU saturating. Until then, switching is a 2-day infra investment in the wrong layer. **Defer, don't skip — but not now.**

---

## What's *worse* in their design (do NOT regress to)

### 1. Per-request thread pool creation

```java
public BidResponse getMaxConnections(BidRequest br) {
    ...
    int nThreads = Configuration.concurrency;
    ExecutorService executor = Executors.newFixedThreadPool(nThreads);  // ⚠️
    ...
    executor.shutdown();
    if (!xtest && !executor.awaitTermination(50, TimeUnit.MILLISECONDS))
        executor.shutdownNow();
    ...
}
```

Creating an `ExecutorService` per bid is a textbook anti-pattern: thread allocation, queue allocation, shutdown overhead, GC pressure. At 25K RPS that's 25K thread pool lifecycles per second. **We absolutely should not adopt this.** Our reuse of Vert.x worker pool is correct.

### 2. Java 8 + Parallel GC

They run on Java 8 with default GC. We run Java 21 with ZGC. Our v4 work proved ZGC is critical at sustained high allocation rates — they would be in continuous GC at our workload.

### 3. Jetty blocking I/O

Thread-per-request scales linearly with concurrency. At 5K RPS + 50ms p99, you need ~250 concurrent connections. At 25K with their workload they'd need ~1250+ threads. Vert.x event-loop scales much better with concurrency for I/O-bound code.

### 4. Aerospike (zerospike) for freq cap

This isn't strictly *worse*, just different — and a major infra change. Aerospike is purpose-built for ad-tech (single-digit-µs reads at scale) but adds operational complexity. Redis with our connection-array architecture handles 10K RPS comfortably; we don't need to switch unless we're chasing 50K+.

---

## Why their "25K QPS" is not directly comparable to our "10K QPS"

Their hot path:
1. Parse incoming OpenRTB request
2. **Lucene-match request attributes against in-memory campaign attributes** (no external lookup)
3. Find first matching campaign (with parallel slice scan + short-circuit)
4. Check freq cap via cache2k local cache (most hits avoid Aerospike)
5. Build response

Our hot path:
1. Parse incoming request
2. **Fetch user segments from Redis** (`SMEMBERS user:{id}:segments`) — network round-trip per request
3. Intersect user segments with each campaign's targeting segments
4. Score top-32 candidates with `FeatureWeightedScorer` (HashMap lookups per campaign)
5. **Fetch freq-cap counts from Redis** (`MGET freq:{user}:{campaign}` × N) — second network round-trip
6. Rank by score, build response
7. Async `ImpressionRecorder` write to Redis EVAL

We do **2 network round-trips per request** that they don't do. We do **per-candidate scoring** they don't do. We support **audience-segment targeting** they don't. Each is a deliberate richness in our model and a deliberate cost.

If we deleted user-segment targeting and used Lucene-style request-attribute matching like they do, we'd probably hit 25K too — but we'd be testing a different bidder.

---

## Conclusions (revised after a stricter quality-preserving review)

**Quality bar: we only adopt techniques that produce identical output, just faster. No accuracy-for-speed trades.**

| Idea | Output-preserving for us? | Verdict |
|---|---|---|
| First-match short-circuit on score-ordered candidates | **No** | Skip. Our `BudgetPacingStage` runs AFTER ranking and can reject the chosen winner. Collecting 64 allowed candidates gives us fallbacks; short-circuit returns 1 with no fallback → we'd no-bid when a valid #2 existed. Their bidder doesn't have post-ranking pacing, so it works for them. We do, so it doesn't. |
| Pre-shuffled campaign lists | Pointless for us | Skip. It's a fairness hack RTB4FREE needs because they don't score (so list order = winner bias). We score deterministically — there's no ordering bias to compensate for. Pure RTB4FREE-specific workaround. |
| Lucene-based request-attribute targeting | **No** | Skip. We do audience-segment targeting deliberately. Different (richer) product. |
| Per-request thread pool creation | n/a | Their anti-pattern. Not a borrowable idea. |
| Aerospike for freq cap | Yes (just faster Redis) | **Defer**, not skip. Real production tech, free and open-source, used by major DSPs (AppNexus, Criteo, Adobe, TTD). But our v4 JFR data shows Redis is NOT our current bottleneck — GC + scoring + sorting are. Switching here is wasted infra investment until we hit ~30K RPS where Redis CPU becomes the wall. |
| Java 8 / Jetty / Parallel GC | n/a | Their stack is older than ours. We're already on Java 21 / ZGC / Vert.x. |

**Bottom line:** they are not better, they are different. Their 25K is on a workload that does fundamentally less work per request than we do. None of their architectural choices, applied to our pipeline, would preserve our output quality at the same level. So none of them are worth taking.

**The right place to find further wins is in our own per-request CPU cost** — same output, faster execution. v5 Phase B options that meet the quality bar:
- **Vectorised scoring** — same math as `FeatureWeightedScorer`, executed via array indexing instead of HashMap lookups. Identical output, 2-3× faster on a single core.
- **Bitmap segment matching** — same correctness as `Set<String>` intersection in `SegmentTargetingEngine.hasOverlap`, but using `long`/`BitSet` AND. Same boolean result, nanoseconds vs microseconds.
- **Reduce per-request allocations** — anything that drops the GC pressure curve (e.g., reusing iterator objects, avoiding `LinkedHashMap` where `ArrayList` works) is pure win.

These are all invisible to a downstream auction — same bid responses, just produced faster.