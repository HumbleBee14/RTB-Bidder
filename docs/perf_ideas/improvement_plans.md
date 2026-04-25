# Notes — Performance Improvement Plan

A forward-looking guide for extending per-instance throughput beyond the
~5K RPS saturation knee surfaced in [LOAD-TEST-RESULTS-v3.md](../LOAD-TEST-RESULTS-v3.md).

This document is **both a plan and an explanation**. For each candidate
improvement it covers:

1. The current problem it solves (with evidence from Run 3)
2. What concrete code or configuration changes are required
3. Why the change addresses the bottleneck (the mechanism)
4. Expected gain, with realistic caveats
5. Effort, risk, and rollout considerations
6. How we'd verify the improvement actually worked

The four improvements are listed in **recommended order of execution**:
biggest bang-for-buck first, sweeping refactor last.

---

## Context — what Run 3 told us

Run 3 demonstrated:

| Rate | Outcome |
|---|---|
| 100 RPS | p99 = 3.91 ms — fully healthy |
| 1,000 RPS (ramp) | p99 = sub-4 ms — fully healthy |
| **5,000 RPS** | **p99 = 63.9 ms** — soft saturation knee, 30% over SLA |
| 10,000 RPS | p50 = 56 ms, every request times out, bid rate 2% |
| 50,000 RPS | bid rate 0.01% — resource collapse |

Errors stayed at zero throughout — the bidder degrades gracefully by
returning `NoBidReason.TIMEOUT` rather than crashing. But the **first
SLA breach is at ~5K RPS**, and we have evidence about where the
bottleneck lives:

- **Lettuce single shared connection** per Redis client. Every MGET (~278
  keys per bid) goes through one TCP socket. At 5K RPS this is ~5K
  commands/sec on one socket plus one Lettuce dispatcher thread.
- **GC pauses climb to ~2,700/min** at 5K RPS — sub-ms per pause but the
  concurrent phases consume CPU.
- **Pipeline blocking** — worker threads block on Redis for ~1ms per call.
  At 5K RPS we need ~5 concurrent workers (we have 72), so we're not
  saturating workers — yet.
- **Event loop** stays under 2 ms lag — not the bottleneck.

The improvements below address each bottleneck in priority order.

---

## 1. Switch Lettuce sync API → async API (single connection retained)

**Priority: highest. Effort: ~1 day. Expected gain: 5K → 20-30K+ RPS.**

> ⚠ **This section was rewritten after research.** Original draft proposed a
> connection pool; that turned out to contradict Lettuce's own published guidance.
> The real headline win is the **sync-to-async API switch**, not pooling.

### Current problem

[`RedisUserSegmentRepository`](../src/main/java/com/rtb/repository/RedisUserSegmentRepository.java)
and [`RedisFrequencyCapper`](../src/main/java/com/rtb/frequency/RedisFrequencyCapper.java)
both use Lettuce's **synchronous** API on a single shared connection:

```java
this.connection = client.connect();
this.commands   = connection.sync();         // ← synchronous API
// usage:
Set<String> segs = commands.smembers(key);   // blocks calling thread until response
```

Lettuce's published per-connection throughput characterisation:

| API | Per-connection ceiling |
|---|---|
| **Sync** (`.sync()`) | **~5,000 RPS** — matches our observed knee exactly |
| Async (`.async()`) with default auto-flush | ~100,000 ops/sec |
| Async with manual batched flush | 470K-800K ops/sec |

Sources:
- [Lettuce Pipelining and command flushing](https://github.com/lettuce-io/lettuce-core/wiki/Pipelining-and-command-flushing)
- [AWS — Optimize Redis client performance for ElastiCache](https://aws.amazon.com/blogs/database/optimize-redis-client-performance-for-amazon-elasticache/)

Our observed 5K saturation knee aligns precisely with the sync-API ceiling.
That isn't coincidence — that's the bottleneck.

### Why connection pooling is NOT the answer

Lettuce's official guidance (verbatim summary from the
[Connection Pooling wiki](https://github.com/redis/lettuce/wiki/Connection-Pooling-5.1)):

> "Lettuce is thread-safe by design which is sufficient for most cases.
> All Redis user operations are executed single-threaded. Using multiple
> connections does not impact the performance of an application in a
> positive way."

Pooling is recommended **only** for blocking commands (`BLPOP`/`BRPOP`/
`XREAD BLOCK`), Redis transactions (`MULTI/EXEC`), or Pub/Sub. None apply
to our `MGET`/`SMEMBERS`/`GET`/`EVAL` workload. Pooling would add complexity
without measurable gain — and could regress latency by inserting borrow/return
overhead on every call.

### What actually changes — the async-API switch

Single connection retained. The change is the API style and how stages
consume the result.

#### In [`RedisUserSegmentRepository`](../src/main/java/com/rtb/repository/RedisUserSegmentRepository.java)

```java
// Before
private final RedisCommands<String, String> commands;             // sync
// after constructor:
this.commands = connection.sync();
// usage:
Set<String> segs = commands.smembers("user:" + userId + ":segments");

// After
private final RedisAsyncCommands<String, String> commands;        // async
// after constructor:
this.commands = connection.async();
// usage (option A — block on future, simplest fix, still gets pipelining win):
RedisFuture<Set<String>> future = commands.smembers("user:" + userId + ":segments");
Set<String> segs = future.get(50, TimeUnit.MILLISECONDS);  // SLA-bounded wait
```

#### In [`RedisFrequencyCapper`](../src/main/java/com/rtb/frequency/RedisFrequencyCapper.java)

The MGET batch becomes:

```java
// Before
List<KeyValue<String, String>> results = commands.mget(keys);

// After
RedisFuture<List<KeyValue<String, String>>> future = commands.mget(keys);
List<KeyValue<String, String>> results = future.get(50, TimeUnit.MILLISECONDS);
```

The calling worker thread still blocks on the future, **but the connection's
dispatcher is no longer one-at-a-time-per-thread.** Lettuce now pipelines
commands from concurrent worker threads through the single TCP socket without
forcing serialization.

### Why this works without a pool — the mechanism

With sync API: each `.sync().mget()` call writes one command, blocks the
calling thread, reads one response, returns. From the connection's perspective
there's one outstanding command at any time per caller, and the connection's
internal queue gets drained one-at-a-time per thread.

With async API: every `.async().mget()` call writes immediately and returns
a future. The connection's dispatcher pipelines them — multiple commands in
flight on the socket at once, responses correlate back to the right future
via Redis's strict ordering guarantee. With 72 worker threads each calling
`.async().mget(...).get()`, **up to 72 commands can be in flight on the
single connection simultaneously**.

The 20× throughput jump (5K → 100K) is exactly this: many in-flight
commands per connection, not many connections.

### Expected gain

- Saturation knee: **~5K → ~20-30K RPS** (likely higher; bounded next by
  scoring CPU or GC, not Redis client)
- p99 at 5K RPS: should drop from 64 ms back into single-digit ms range
- 10K and 25K stress: should now pass the 50 ms SLA threshold
- 50K RPS: probably still fails — but the next bottleneck is somewhere else
  entirely (likely worker thread CPU or GC)

### Effort & risk

| | |
|---|---|
| Code change | ~30-60 lines across 2 repositories — smaller than connection pool would have been |
| Risk | Low — both Lettuce APIs are stable; only differences are method signatures |
| Rollout | Single commit, no feature flag needed |
| Compatibility | None at the public interface level (`UserSegmentRepository` and `FrequencyCapper` unchanged); internals only |
| Dependencies | None added — `RedisAsyncCommands` ships with Lettuce |

### Optional second step — manual flush batching

If even higher throughput is needed, Lettuce supports disabling auto-flush
and batching multiple commands per TCP write:

```java
connection.setAutoFlushCommands(false);
RedisFuture<...> f1 = commands.mget(keys1);
RedisFuture<...> f2 = commands.mget(keys2);
connection.flushCommands();   // one TCP write for both MGETs
```

This pushes per-connection ceiling into the 470K-800K ops/sec range. Only
worth doing if measurement shows we're hitting the auto-flush plateau.

### How to verify

Run the existing stress curve after the change:

```bash
make load-test-stress-5k
make load-test-stress-10k
make load-test-stress-25k
make load-test-stress-50k    # may now pass for the first time
```

Pass criteria:
- 5K stress: p99 should drop to <10 ms
- 10K stress: should pass thresholds
- 25K stress: likely passes; otherwise reveals the next bottleneck
- 50K stress: depends on what bottleneck is next (improvement #3 or #2)

In Grafana, the
`redis_client_command_duration_seconds{command="mget",quantile="0.99"}`
panel should now show much lower per-command latency — the win is in
client-side pipelining, not Redis server-side processing.

---

## 2. JVM heap bump 512 MB → 2 GB + ZGC tuning

**Priority: medium. Effort: ~30 minutes. Expected gain: 10-20% under load.**

### Current problem

The current heap is sized for a small per-pod deployment (a perfectly
reasonable production default). But it produces visible signal in the v3
panels at high RPS:

- GC pauses climb to **~2,700/min** at 5K RPS (vs ~10/min at idle)
- Heap "Used" sawtooth deepens — each cycle reclaims more, runs more often
- Concurrent GC phases consume CPU during the same window where p99 climbs

ZGC stop-the-world pauses stay sub-millisecond. They're not the issue.
The issue is the **concurrent phases** (mark, relocate, reference-processing)
running ~45 times per second under high allocation rate. Each concurrent
phase steals cores from request handlers.

### What changes

Update `JVM_PROD` flags in [Makefile](../Makefile):

```makefile
# Before
JVM_PROD := $(JVM_BASE) \
            -Xms512m -Xmx512m \
            -XX:+AlwaysPreTouch \
            -Xlog:gc*:file=results/gc.log:time,uptime,level,tags

# After
JVM_PROD := $(JVM_BASE) \
            -Xms2g -Xmx2g \
            -XX:+AlwaysPreTouch \
            -XX:SoftMaxHeapSize=1500m \
            -Xlog:gc*:file=results/gc.log:time,uptime,level,tags
```

Three changes:

1. **`-Xms2g -Xmx2g`** — fixed at 2 GB. We've seen ~250 MB peak used at 5K RPS;
   2 GB gives 8× headroom which absorbs allocation bursts at higher rates.
2. **`+AlwaysPreTouch`** retained — touches all heap pages at startup so
   page faults don't pollute request latency.
3. **`-XX:SoftMaxHeapSize=1500m`** — tells ZGC to *target* using 1.5 GB but
   allow up to 2 GB if needed. ZGC will trigger collection earlier and more
   often around the soft max, smoothing out the cycle pattern instead of
   running cycles back-to-back when allocation pressure spikes.

The `JVM_LOAD` (load-test) profile inherits these via `JVM_PROD`.

### Why it helps

ZGC's allocation-rate vs heap-size trade-off:

```
GC frequency  ≈  allocation_rate / (heap_size - live_set)

At 5K RPS:
  allocation_rate ≈ 350 MB/cycle (extrapolated from v2's ~7 MB/cycle at 1K)
  live_set        ≈ 50 MB (steady)

  With 512 MB heap:  GC every (512 - 50) / 350 = 1.3 cycles per second × 60 = ~78/min target
                     (we observed 2,700/min — ZGC fires more aggressively to keep up)

  With 2 GB heap:    GC every (2000 - 50) / 350 = 5.6 cycles per second × 60 = ~336/min target
                     ZGC has more buffer → fewer concurrent phases needed
                     → more CPU available for request handling
```

Concrete benefits:

- **Fewer concurrent GC phases firing per second** — more CPU left for the
  bid pipeline
- **Lower variance** — current heap forces ZGC into reactive mode (firing
  whenever allocation hits the trigger). With more headroom, ZGC's
  *proactive* mode takes over: schedules collections at predictable intervals
  during quieter moments.
- **Slight reduction in p99 tail variance** — less chance of a request
  landing during a concurrent-phase CPU steal

### Expected gain

- Throughput: **+10-20%** at the saturation knee
- p99 tail variance: noticeably tighter (max latency outliers reduce)
- Standalone, this won't move the knee dramatically — pairs well with #1

### Effort & risk

| | |
|---|---|
| Code change | One Makefile line (+ optional `SoftMaxHeapSize` flag) |
| Risk | Negligible — bigger heaps are safer, not riskier |
| Rollout | Single commit; verifiable instantly with `make run-prod-load` |
| Compatibility | None |
| Memory cost | +1.5 GB resident on the bidder host (acceptable on production VMs; consider for tight container budgets) |

### How to verify

Re-run the stress at 5K RPS (assuming improvement #1 is also done):

```bash
# Before: with 512 MB heap
make load-test-stress-5k
# Capture: GC Pauses/min in Grafana, p99 from k6

# Apply heap change → make rebuild → restart bidder

# After: with 2 GB heap
make load-test-stress-5k
# Compare: same Grafana panel, same k6 metrics
```

Look for:
- GC Pauses/min reduced from ~2700 to ~500-1000 at the same RPS
- Heap Used sawtooth amplitude reduced
- p99 / p99.9 latency tightened by 10-20%

---

## 3. Async pipeline — Future-chained stages over Lettuce async

**Priority: medium-high (gated on #1 first). Effort: 1-2 weeks. Expected gain: 15K → 25-50K RPS.**

### Current problem

Even with a connection pool (#1), every pipeline stage that touches Redis
**blocks the worker thread** for the round-trip duration:

```java
// Current: synchronous
public Set<String> getSegments(String userId) {
    return cache.get(userId, delegate::getSegments);  // blocks worker on cache miss
}
```

At 1ms per Redis round-trip and 72 worker threads, theoretical max is
72 × 1000 = 72K RPS — but only if every request is uniform. In practice:

- Cache miss + slow Redis response → worker blocks 5-10ms
- Multiple cache misses in flight → workers stack up
- Worker pool saturation reduces throughput non-linearly

This is the architectural cap. Async pipeline removes it entirely.

### What changes

Three sweeping but mechanical changes:

#### 3a. Change `PipelineStage.process()` signature

```java
// Before
public interface PipelineStage {
    void process(BidContext ctx);
    String name();
}

// After
public interface PipelineStage {
    Future<Void> process(BidContext ctx);   // io.vertx.core.Future
    String name();
}
```

Synchronous stages (RequestValidationStage, RankingStage, ResponseBuildStage,
BudgetPacingStage) just wrap their existing logic in `Future.succeededFuture()`.
Async stages (UserEnrichmentStage, FrequencyCapStage) use Lettuce's async API.

#### 3b. Change `BidPipeline.execute()` to chain futures

```java
// Before: linear synchronous loop
public BidContext execute(BidRequest request, long startNanos) {
    BidContext ctx = pool.acquire(...);
    for (PipelineStage stage : stages) {
        if (ctx.isAborted()) break;
        stage.process(ctx);
    }
    return ctx;
}

// After: future composition
public Future<BidContext> execute(BidRequest request, long startNanos) {
    BidContext ctx = pool.acquire(...);
    Future<Void> chain = Future.succeededFuture();
    for (PipelineStage stage : stages) {
        chain = chain.compose(v -> ctx.isAborted()
            ? Future.succeededFuture()
            : stage.process(ctx));
    }
    return chain.map(v -> ctx);
}
```

#### 3c. Replace Lettuce sync calls with async

```java
// Before
public Set<String> getSegments(String userId) {
    return commands.smembers("user:" + userId + ":segments");
}

// After
public Future<Set<String>> getSegments(String userId) {
    RedisFuture<Set<String>> redisFuture =
        connection.async().smembers("user:" + userId + ":segments");
    return Future.fromCompletionStage(redisFuture.toCompletableFuture());
}
```

Plus `BidRequestHandler` no longer needs `executeBlocking()` — it just chains
the pipeline future directly:

```java
pipeline.execute(request, startNanos)
    .onSuccess(ctx -> finishRequest(routingCtx, ..., ctx))
    .onFailure(err -> noBid(routingCtx, ..., NoBidReason.INTERNAL_ERROR));
```

### Why it helps

Synchronous blocking forces a 1:1 mapping between worker threads and
in-flight requests. A worker blocked on Redis is a slot lost. Async chains
through `Future` composition mean a worker thread fires a Redis call,
returns the carrier-thread to the pool, and resumes only when the response
arrives — many in-flight requests can share each carrier.

> ⚠ **Honest caveat**: there is no clean head-to-head published benchmark
> of "Vert.x sync-on-worker" vs "Vert.x fully-async" pipelines — TechEmpower
> only publishes the async variant since that's what the framework is
> designed for. The expected gain below is grounded in the **mechanism**
> (one fewer thread context switch and one fewer queue per pipeline stage),
> not in a cited multiplier. Treat the numbers as ceiling estimates, not
> guarantees.

Where the win shows up most reliably:

- **Tail latency (p99/p99.9/max)** improves more than mean throughput.
  Removing the worker-thread queue eliminates a class of tail spikes
  (queueing bursts) that don't move p50 but dominate p99.
- **Throughput under saturation** can grow by some multiple, but the
  exact multiple depends on what the *next* bottleneck is. If it's CPU
  for scoring, async won't help at all. If it's worker-thread availability
  (which it is for us at 50K+ RPS), async removes the constraint entirely.

References:
- [TechEmpower benchmark — Vert.x consistently top-3 in plaintext/JSON](https://www.techempower.com/benchmarks/)
  (confirms async Vert.x is fast in absolute terms)
- [Mastercard — The Vert.x Worker Model](https://developer.mastercard.com/blog/the-vertx-worker-model/)
  (explicit framework guidance: worker verticles are intended for blocking
  third-party calls, not as a general design)

### Expected gain

- Saturation knee: probably moves into the **25-50K RPS range**, but the
  next bottleneck (CPU for scoring, GC, or k6 client itself) determines
  the actual ceiling.
- **p99 tail latency improves disproportionately** — this is the more
  reliable win than raw throughput.
- The bidder finally matches the "fully reactive" Vert.x ideal — same
  design pattern as the highest-throughput services in the TechEmpower
  rankings.

### Effort & risk

| | |
|---|---|
| Code change | ~500-1000 lines across pipeline interface, all 9 stages, both Redis repos, ResilientRedis circuit breaker, BidRequestHandler, tests |
| Risk | **High** — touches the entire request flow. Subtle bugs (orphaned futures, exception propagation, context lifecycle) can leak threads or corrupt the pool. |
| Rollout | Cannot ship as a single commit cleanly — would need a feature flag (`pipeline.async.enabled`) and dual code paths during transition. Consider doing it as Phase 18. |
| Compatibility | Breaking interface change for `PipelineStage`. All existing tests need updating. |
| Operational | Async stack traces are harder to debug than synchronous ones — invest in good logging + metrics first |

### Pre-requisites before starting

1. **Improvement #1 must be done first.** Without a connection pool, async
   Redis still serializes through one socket — async doesn't help.
2. **Comprehensive tests** for every stage. Async refactors break in subtle
   ways; the tests are the safety net.
3. **Distributed tracing** (OpenTelemetry) ideally added first — async
   stack traces are useless for debugging without trace IDs.

### How to verify

After the refactor:

```bash
# Should now PASS (currently fails)
make load-test-stress-25k
make load-test-stress-50k

# Likely fails but with much better numbers than current
make load-test-stress-100k
```

Watch in Grafana:
- Worker thread count: should stay much lower under same load
- Event Loop Lag: should remain near-zero even at 50K RPS
- BidContext Pool: should grow to absorb concurrency, then plateau

---

## 4. Vectorised scorer — batched feature computation

**Priority: low (lowest gain at default config; matters if ML scoring is enabled). Effort: 2-3 days.**

### Current problem

[`FeatureWeightedScorer`](../src/main/java/com/rtb/scoring/FeatureWeightedScorer.java)
runs one `score()` call per candidate:

```java
for (AdCandidate candidate : ctx.getCandidates()) {
    double score = scorer.score(candidate, request);  // one call per candidate
    candidate.setScore(score);
}
```

At 5K RPS × ~280 candidates per bid = 1.4M scoring calls/sec. The arithmetic
itself is trivial (3 multiplications), so this isn't the dominant bottleneck.
But:

- Each call has method-dispatch overhead (megamorphic call site if multiple
  scorer types are wired in)
- Each call constructs/destructs intermediate values that GC has to clean up
- JIT can't auto-vectorise the loop because the `score()` method boundary
  prevents it

For the default `FeatureWeightedScorer` this is ~5-10% of total time. For
[`MLScorer`](../src/main/java/com/rtb/scoring/MLScorer.java) (ONNX inference)
it's a much bigger fraction — ONNX Runtime supports batch inference natively
and we currently don't exploit it.

### What changes

Add a batch interface to `Scorer`:

```java
public interface Scorer {
    String name();

    // Existing per-candidate API (kept for backward compat)
    double score(AdCandidate candidate, BidRequest request);

    // New batch API with default fallback
    default double[] scoreBatch(List<AdCandidate> candidates, BidRequest request) {
        double[] scores = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            scores[i] = score(candidates.get(i), request);
        }
        return scores;
    }
}
```

`ScoringStage` uses the batch API:

```java
double[] scores = scorer.scoreBatch(ctx.getCandidates(), ctx.getRequest());
for (int i = 0; i < scores.length; i++) {
    ctx.getCandidates().get(i).setScore(scores[i]);
}
```

For `FeatureWeightedScorer`, the batch implementation can use the
[Java Vector API](https://openjdk.org/jeps/448) (incubator since Java 16,
stable since Java 25):

```java
@Override
public double[] scoreBatch(List<AdCandidate> candidates, BidRequest request) {
    double[] overlap = computeOverlapBatch(candidates, request);   // SIMD-friendly
    double[] price   = extractPriceBatch(candidates);              // SIMD-friendly
    double[] pacing  = extractPacingBatch(candidates);             // SIMD-friendly
    double[] result  = new double[candidates.size()];

    var species = DoubleVector.SPECIES_PREFERRED;
    int upperBound = species.loopBound(candidates.size());
    int i = 0;
    for (; i < upperBound; i += species.length()) {
        var o = DoubleVector.fromArray(species, overlap, i);
        var p = DoubleVector.fromArray(species, price, i);
        var pc = DoubleVector.fromArray(species, pacing, i);
        o.mul(p).mul(pc).intoArray(result, i);    // SIMD multiply
    }
    for (; i < candidates.size(); i++) {
        result[i] = overlap[i] * price[i] * pacing[i];
    }
    return result;
}
```

For `MLScorer`, the batch implementation passes a [batch tensor](https://onnxruntime.ai/docs/api/java/index.html)
to ONNX Runtime — single inference call returns scores for all candidates
in one C++ kernel invocation, far cheaper than 280 separate inference calls.

### Why it helps

For `FeatureWeightedScorer`:
- SIMD (Single Instruction Multiple Data) executes 4-8 multiplications per
  CPU cycle vs 1 in scalar mode
- Loop body is tighter — better instruction cache utilisation
- No per-candidate allocation
- JIT C2 has more visibility into the loop and can apply additional
  optimisations

For `MLScorer`:
- ONNX batch inference reuses tensor allocations across the batch
- C++ kernel does the matmul once on the full batch instead of 280 times
  on single rows — typically 5-20× faster for dense networks

### Expected gain

| Scorer | Current cost (per bid at 5K RPS) | After vectorisation |
|---|---|---|
| FeatureWeightedScorer | ~0.05 ms | ~0.01 ms |
| MLScorer (XGBoost via ONNX) | ~0.5-2.5 ms | ~0.1-0.3 ms |
| ABTestScorer (mix) | proportional | proportional |
| CascadeScorer (cheap-then-expensive) | dominated by ML cost | major win on ML side |

For default config (FeatureWeightedScorer): **5-10% throughput improvement** —
real but not transformative.

For ML scoring enabled: **30-50% throughput improvement** at the scoring stage,
which is currently the most expensive stage when active.

### Effort & risk

| | |
|---|---|
| Code change | ~200-400 lines: new batch method on Scorer interface + 4 implementations + ScoringStage update |
| Risk | Low for FeatureWeightedScorer; moderate for MLScorer (ONNX tensor lifecycle has been a source of native-memory leaks) |
| Rollout | Backward-compatible — default `scoreBatch` falls back to single-call loop. Existing scorers continue to work. |
| Compatibility | None at the user-facing layer; internal `Scorer` interface gains a method |
| Dependency | Java Vector API is **still in incubator** as of JDK 25 — currently the [10th Incubator (JEP 508)](https://openjdk.org/jeps/508), with [JEP 529](https://openjdk.org/jeps/529) re-incubating for the next release. Requires `--add-modules jdk.incubator.vector` and accepting that signatures may change between LTS releases until Project Valhalla's value-types land. |

### How to verify

Microbenchmark first (JMH):

```bash
mvn jmh:benchmark -Dbenchmark=FeatureWeightedScorerBenchmark
```

Then full stress curve:

```bash
make load-test-stress-5k    # check Pipeline Stage Latency for "Scoring"
```

Compare `pipeline_stage_latency_seconds{stage="Scoring"}` p99 before and after.
For ML config (`SCORING_TYPE=ml`), the win should be 5-20× on that single panel.

---

## 5. Netty direct-buffer pooling (cheap quick win for Vert.x HTTP)

**Priority: medium-low. Effort: ~1 hour. Expected gain: marginal but free.**

Vert.x uses Netty under the hood for its HTTP server. Netty allocates direct
(off-heap) `ByteBuf`s for incoming bid request bodies and outgoing responses.
Each allocation under high RPS is a measurable cost — direct memory allocation
involves OS calls, and pooled vs unpooled buffer allocators have a 5-10% throughput
gap on HTTP+JSON workloads.

### What changes

Two JVM system properties (set via `JVM_BASE` in [Makefile](../Makefile)):

```makefile
JVM_BASE := --sun-misc-unsafe-memory-access=allow \
            --enable-native-access=ALL-UNNAMED \
            -XX:+UseZGC \
            -Dio.netty.allocator.type=pooled \
            -Dio.netty.allocator.numDirectArenas=18
```

- `io.netty.allocator.type=pooled` — uses Netty's `PooledByteBufAllocator`
  (default in modern Netty for direct buffers, but explicit is safer)
- `io.netty.allocator.numDirectArenas=18` — one arena per CPU core (default
  is `2 × cores` which can over-allocate; tuning to exactly cores avoids
  cross-arena contention)

### Why it helps

Pooled allocator amortises direct-buffer allocation across reuse. At 5K
RPS each request body is ~500-1500 bytes; without pooling, that's ~7 MB/s
of off-heap allocation that the OS has to repeatedly carve from the heap.
With pooling, the same buffers are recycled across requests.

The `numDirectArenas` knob: each arena is a thread-local allocator zone.
Default `2 × cores` is conservative; since Vert.x has exactly N event-loop
threads (one per core), tuning arenas to match cores avoids contention
between worker threads sharing arenas.

### Expected gain

- Throughput: **+3-8% sustained**, more during memory-pressured moments
- p99/p99.9 tail: tighter under sustained load
- Stand-alone: not transformative; pairs cleanly with #1 and #2

### Effort & risk

| | |
|---|---|
| Code change | Two flags in Makefile |
| Risk | Negligible — pooled allocator is the Netty production default |
| Rollout | Single commit |
| Compatibility | None |

### How to verify

Before/after at the 5K stress level:
- `process_resident_memory_bytes` in Prometheus — should be slightly lower
  with less off-heap churn
- p99 / p99.9 tail in any high-RPS stress test — minor improvement

---

## 6. Application-level backpressure / load shedding

**Priority: high (production-readiness, not throughput). Effort: ~2-3 days.**

### The problem this solves

Discovered during the async-API investigation
([investigation log entry 2026-04-25](../perf/investigation-log.md)):
the bidder enters a **thrashing** state under sustained overload and **does
not recover**. Once the worker pool / Lettuce dispatcher saturate at high
RPS, the queue grows faster than it drains, every request times out at the
50 ms SLA, and the system stays in this bad mode until the JVM restarts.

The bidder currently has no application-level backpressure. It accepts
every incoming request and trusts the SLA timeout to abort overruns. That
works at low load. Under sustained overload it produces:

- Queue grows unboundedly relative to drain rate
- All worker threads parked waiting on the saturated downstream
- Almost all requests TIMEOUT
- System cannot self-recover even when load subsides briefly

### What changes

Add a short HTTP layer that sheds load when the bidder is overloaded.
Several signals could drive shedding decisions:

| Signal | Threshold (example) | Response |
|---|---|---|
| Vert.x worker pool queue depth | > 2× pool size | Return `429 Too Many Requests` |
| Recent p99 latency (rolling 10s) | > 40 ms (i.e., approaching SLA) | Reduce admission rate |
| BidContext pool exhaustion | pool empty + new allocation count climbing | Return `503 Service Unavailable` |
| CPU load | > 90% sustained | Drop traffic at source |

Implementation likely involves:

- Adding a counter for "in-flight requests" in `BidRequestHandler`
- Comparing against a configurable `INFLIGHT_HIGH_WATERMARK`
- If exceeded: return 429 immediately without entering the pipeline
- Decrement counter on response or on error

### Why this matters specifically for our case

In production RTB, exchanges respect HTTP 429 by routing to other bidder
instances. A bidder that returns 429 quickly when overloaded is **healthier
for the ecosystem** than one that accepts work and then fails the SLA — both
result in no bid for that auction, but the 429 path doesn't burn bidder
resources or pollute downstream caches.

Even more importantly: 429 lets the bidder **recover**. A few seconds of
shedding lets the queue drain, dispatcher catches up, system returns to
healthy mode. Without shedding, the system stays thrashing forever.

### Expected effect

- **The bistable behaviour disappears.** Once load drops below the
  watermark (or after some shed pressure), bidder returns to healthy state.
- Maximum sustained throughput unchanged — backpressure doesn't add capacity.
- p99 stays under SLA at any load (because anything above the watermark is
  rejected, not accepted-then-failed).
- 429 rate becomes a real production signal — it tells you "this instance is
  near capacity, scale out."

### Effort & risk

| | |
|---|---|
| Code change | ~100-200 lines: counter + watermark check in BidRequestHandler + a metric |
| Risk | Low — fails closed at the door, no impact on healthy paths |
| Rollout | Single commit, controlled by env-var threshold (set high to disable in early rollout) |

### Pre-requisite

Identify the actual bottleneck first (Lettuce dispatcher per JFR analysis),
because the watermark threshold should track that bottleneck — not be a
random number. Backpressure tuned against the wrong signal is worse than no
backpressure.

---

## Things deliberately not on this list

Improvements that sound good but aren't worth doing now, with reasons:

| Idea | Why deferred |
|---|---|
| **Lettuce connection pool** | Lettuce's own docs explicitly recommend against this for non-blocking GET/MGET/EVAL workloads. Async-API switch (improvement #1) is the real fix. See [Lettuce Connection Pooling wiki](https://github.com/redis/lettuce/wiki/Connection-Pooling-5.1). |
| **Virtual Threads (Project Loom) for the worker pool** | **Specifically a bad fit here.** Netty's "loom carrier mode" (4.9) is experimental; both Quarkus and Micronaut flag the integration as a research project. Running virtual threads on Netty event loops is anti-pattern for sub-50ms SLAs. References: [Netty issue 14636 — virtual thread support status](https://github.com/netty/netty/issues/14636), [Micronaut Loom carrier mode](https://micronaut.io/2025/06/30/transitioning-to-virtual-threads-using-the-micronaut-loom-carrier/). Stay with event-loop async. |
| **Shenandoah GC instead of ZGC** | Shenandoah's sub-10 ms pauses are worse than ZGC's sub-1 ms. ZGC is also OpenJDK's strategic GC direction. Already using ZGC; pick it and move on. [Morling — Lower Java tail latencies with ZGC](https://www.morling.dev/blog/lower-java-tail-latencies-with-zgc/) |
| **Generational ZGC explicit flag** | Already automatic in JDK 25 — `-XX:+UseZGC` is enough; the explicit `-XX:+ZGenerational` flag was removed in JDK 24 (we hit this in Phase 17 setup). Current Makefile is correct. |
| **Off-heap segment cache** (e.g. Chronicle Map) | Caffeine already keeps GC sub-millisecond. Solving a problem we don't have. |
| **Sharded Redis** (multiple Redis instances by user-hash) | Production-scale concern. Single bidder pod doesn't need this; horizontal scale of bidders deals with it. |
| **CPU pinning event loops to specific cores** | Marginal at our scale; matters at 100K+ RPS per pod. |
| **Custom JSON codec replacing Jackson** | Already using streaming codec. Jackson is fast enough; replacing adds risk. |
| **Pre-warming the JIT via synthetic traffic at startup** | Operational concern. Real production deployments do this via health-check delays + load-balancer drain logic. |
| **Distributed tracing (OpenTelemetry)** | Operational, not perf. **Should be done as a prerequisite for improvement #3** (async pipeline) — async stack traces are useless without trace IDs — but on its own it's an observability item, not a throughput one. |
| **Rust/C++ rewrite** | Different project. Java with proper async + tuned ZGC lands within 2-3× of native performance for this workload (RTB4FREE achieves 25K QPS/node on Java 8; tuned modern Java should do better). |

---

## Recommended sequence

If we wanted to push the bidder beyond the current 5K knee:

### Round 1 — small-effort wins (~1 day total)

1. **Switch Lettuce sync API → async API** — biggest single gain (~5K → 20-30K RPS),
   smallest effort. Lettuce's own published numbers show this is the difference
   between 5K ops/s/connection (sync) and 100K ops/s/connection (async).
2. **Heap bump 512 MB → 2 GB + ZGC soft max** — trivial Makefile change,
   relieves the 2,700 GC pauses/min we observed at 5K RPS.
3. **Netty pooled direct buffers + tuned arenas** — two JVM flags, free 3-8% win.

Re-run the full stress curve. **Expect knee to move from ~5K to ~20-30K+ RPS.**

If that's enough capacity, STOP HERE and shift focus to operational concerns:
health checks, blue/green deploy, auto-scaling, regional routing. At 25K RPS
per pod, [RTB4FREE-class throughput](https://github.com/RTB4FREE/bidder), 40
pods = 1M aggregate QPS — well within DSP-scale economics. Production RTB
absolutely scales horizontally past this point.

### Round 2 — only if 25K-30K isn't enough

4. **Async pipeline** — sweeping refactor, big tail-latency win, possibly more
   throughput. **Add OpenTelemetry distributed tracing first** — debugging async
   stack traces without trace IDs is nearly impossible. Treat OTel as a
   prerequisite, not a separate concern.

### Round 3 — only if ML scoring is in production use

5. **Vectorised / batched scorer** — minor for `FeatureWeightedScorer` (5-10%),
   but big for `MLScorer` via ONNX Runtime batch inference (5-20× on the
   scoring stage). Only worth doing once ML scoring is the dominant pipeline
   cost.

### Per-pod RTB throughput context (research-cited)

For sanity-checking which round to stop at:

| Reference | Per-pod / per-node QPS |
|---|---|
| [RTB4FREE](https://github.com/RTB4FREE/bidder) (open-source Java bidder, Java 8) | **25K+ QPS/node** (documented) |
| [Moloco DSP](https://www.moloco.com/r-d-blog/challenges-in-building-a-scalable-demand-side-platform-dsp-service) | >5M QPS aggregate; per-pod undisclosed but implicitly very high (cost-sensitive economics) |
| Industry rule of thumb | **~100K QPS** absorbed at ingress without thread stalls is the threshold for a "viable" DSP |
| Aspirational target after Round 1 + 2 | **20-50K RPS per pod** is a defensible band on commodity hardware |

The earlier "250-2K RPS per pod" estimate was too conservative — real numbers
are higher. Round 1 alone should land us in the lower end of the realistic
production band.

---

## What this isn't

This document plans **per-instance throughput improvements**. It does
not address:

- **Horizontal scaling** (load balancer, service discovery, multi-region)
- **Operational maturity** (health checks, deploy strategies, alerting)
- **Cost optimisation** (right-sizing instances, regional placement)
- **Feature work** (new bidding strategies, new ad formats, new ML models)

Those are separate concerns and would each justify their own planning
documents. The improvements above only address the question "how do we
make one bidder process handle more traffic" — which is one slice of
overall RTB engineering, and not even the most impactful slice in
production.
