# Notes — Peak Java Performance Brainstorm

A comprehensive ideation document covering every dimension of Java performance
optimisation that could apply to this bidder, with research-backed verdicts on
each. Goal: extract every ounce of performance from the Java version before
cloning to Rust/C++ for raw-language comparison.

This is an **ideas catalogue** — not all of these will be done, and some are
explicit "do not pursue." The point is to make every option visible with a
reasoned recommendation so we don't leave wins on the table or chase mirages.

For the **prioritised execution plan**, see [notes-perf-improvements.md](notes-perf-improvements.md).
This document goes broader and deeper.

---

## Reference points from research

Before listing techniques, the data points worth anchoring against:

| Reference | Per-instance throughput | Source |
|---|---|---|
| [RTB4FREE](https://github.com/RTB4FREE/bidder) (open-source Java RTB on Java 8) | **25K+ QPS / node** | repo README |
| [Moloco DSP](https://www.moloco.com/r-d-blog/challenges-in-building-a-scalable-demand-side-platform-dsp-service) | aggregate >5M QPS, 1T bids/month, sub-100ms; ML offloaded over gRPC to TensorFlow VMs; uses Bigtable not Redis | R&D blog |
| LMAX Disruptor inter-thread hop | ~52 ns vs ~32,757 ns for `ArrayBlockingQueue` (~630×) | [LMAX wiki](https://github.com/LMAX-Exchange/disruptor/wiki/Performance-Results) |
| LMAX Exchange itself | 6M orders/s on a single JVM thread | [Fowler — LMAX Architecture](https://martinfowler.com/articles/lmax.html) |
| Aeron IPC | 0.25 µs round-trip for 100B messages, 10 µs network, >1M msgs/s/core | [Aeron benchmarks](https://github.com/aeron-io/aeron/wiki/Performance-Testing) |
| simdjson-java vs Jackson | ~3× on Twitter-JSON benchmark (816 → 2,382 ops/s) | [simdjson-java](https://github.com/simdjson/simdjson-java) |
| GraalVM native-image vs HotSpot C2 | **0.8×** at peak (slower!) but 30× faster startup, 2.5× lower memory | [GraalVM perf docs](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/) |
| AppCDS startup | 33–50% reduction (~500ms → ~250ms) | [Red Hat AppCDS](https://developers.redhat.com/articles/2024/01/23/speed-java-application-startup-time-appcds) |

Two non-obvious takeaways from this:

1. **RTB4FREE achieving 25K QPS on Java 8 is our floor, not our ceiling.**
   On Java 25 + ZGC + Vert.x async, we should comfortably exceed it.
2. **GraalVM native-image is slower at peak**, not faster. The win is startup
   and memory footprint. For a long-running bidder we don't care about
   either, so native-image is the wrong lever.

---

## Index — categories covered

1. [Wire format & protocol](#1-wire-format--protocol) — gRPC, Protobuf, HTTP/2/3, OpenRTB binary
2. [JSON serialisation](#2-json-serialisation) — Jackson, DSL-JSON, simdjson
3. [Redis client & data layer](#3-redis-client--data-layer) — sync→async, pipelining, RESP3, Lua, sharding
4. [JVM, GC & memory](#4-jvm-gc--memory) — ZGC tuning, large pages, AppCDS, off-heap
5. [JIT, AOT & runtime](#5-jit-aot--runtime) — tiered compilation, GraalVM JIT, PGO, CDS
6. [Concurrency primitives](#6-concurrency-primitives) — Disruptor, LongAdder, @Contended, lock-free
7. [Network & IO tuning](#7-network--io-tuning) — Netty buffers, HTTP/2, kernel sysctls
8. [OS & hardware tuning](#8-os--hardware-tuning) — CPU pinning, NUMA, huge pages, IRQ affinity
9. [Algorithms & data structures](#9-algorithms--data-structures) — Roaring Bitmaps, Bloom filters, hashing
10. [Library & framework alternatives](#10-library--framework-alternatives) — Helidon Nima, Aeron, Micronaut
11. [HFT-inspired patterns](#11-hft-inspired-patterns) — pinned single-writer, busy-spin, cache-line padding
12. [Architectural patterns](#12-architectural-patterns) — out-of-process ML, sharding, pre-computed indexes
13. [Profiling & verification](#13-profiling--verification) — JFR, async-profiler, JMH

Every category has a verdict at the bottom: **pursue / consider / skip / already done.**

---

## 1. Wire format & protocol

### gRPC + Protobuf for OpenRTB

OpenRTB is an HTTP/JSON spec — exchanges send JSON, expect JSON back. We don't
control the wire format on the ingress side. **Skip.**

### gRPC for inter-service calls

If the bidder ever calls out to a model-serving service or pricing service
(like Moloco offloading ML to TensorFlow VMs over gRPC), Protobuf + HTTP/2
beats JSON+HTTP/1.1 for those internal hops.

- **Verdict: not applicable yet** — we have no internal RPC. **Pursue if/when**
  ML scoring becomes a separate service (Moloco's pattern).

### HTTP/2 multiplexing

Single connection, multiple concurrent streams. Eliminates head-of-line
blocking that HTTP/1.1 keep-alive has. Vert.x supports HTTP/2 via TLS or
prior-knowledge cleartext.

- **Verdict: consider.** OpenRTB exchanges generally use HTTP/1.1 with
  keep-alive — but if the exchange supports H2, enabling it on the bidder's
  HTTP server costs nothing and removes one tail-latency cause.

### HTTP/3 / QUIC

Newer transport. Eliminates TCP handshake latency, recovers faster from
packet loss.

- **Verdict: skip.** Localhost has no packet loss; production exchanges all
  speak HTTP/1.1 or HTTP/2. Niche win for international RTB; not now.

### CapnProto / FlatBuffers / SBE for hot-path internal data

Zero-copy serialisation for the bid pipeline's internal state. Means the
`BidContext` could be backed by a flat memory layout instead of Java objects.

- **Verdict: skip for this codebase.** Our `BidContextPool` already keeps GC
  out of the hot path; the perceived win from off-heap layout is marginal at
  ms-scale SLA. Real value at µs-scale (HFT), not ms-scale (RTB).

---

## 2. JSON serialisation

### Current state

We use a streaming Jackson codec (`BidRequestCodec`, `BidResponseCodec`) — already
much faster than Jackson tree/POJO mode.

### DSL-JSON

Compile-time generated codec, byte-level parsing, near-zero allocations on
deserialisation. In [fabienrenaud's JMH suite](https://github.com/fabienrenaud/java-json-benchmark)
DSL-JSON typically beats Jackson by ~2× on parse and dramatically lower
allocation rate.

- **Win mechanism**: zero-allocation hot path → less GC pressure → tighter p99
  tail (the GC-correlation we already see in stress runs)
- **Cost**: build-time annotation processor + ~200-line codec replacement
- **Verdict: pursue** if/when JSON parsing shows up as a bottleneck in
  profiling. At our current 5K knee it's not — pipeline cost dominates.

### simdjson-java

SIMD-accelerated DOM-style parse. ~3× Jackson on whole-document scan.

- **Win mechanism**: vectorised whole-document parsing
- **Drawback**: DOM-style API doesn't fit selective field extraction in
  OpenRTB (we want specific fields, not the whole tree)
- **Verdict: skip.** Our streaming codec already extracts only what we need;
  a DOM parser is structurally a worse fit even if individual op is faster.

---

## 3. Redis client & data layer

### Lettuce sync → async on the same connection

Already covered in detail in [notes-perf-improvements.md §1](notes-perf-improvements.md).

| API | Per-connection throughput |
|---|---|
| Sync | **~5,000 ops/sec** (matches our knee) |
| Async + auto-flush | ~100,000 ops/sec |
| Async + manual batched flush | 470K-800K ops/sec |

- **Verdict: pursue first.** Highest gain, smallest effort, no architectural change.

### Connection pooling

Lettuce's [own docs](https://github.com/redis/lettuce/wiki/Connection-Pooling-5.1)
explicitly recommend against pooling for non-blocking GET/MGET workloads.
Pooling helps only for `BLPOP`/`MULTI`/Pub-Sub.

- **Verdict: skip.** Already documented as a misconception in
  [notes-perf-improvements.md](notes-perf-improvements.md).

### RESP3 protocol

Lettuce 6+ defaults to RESP3. Main win is push-message multiplexing — halves
connection count for Pub/Sub-heavy workloads. We don't use Pub/Sub.

- **Verdict: already on by default.** No further action.

### Application-layer Redis pipelining (cross-request batching)

Aggregate MGET calls from multiple in-flight bid requests within a small
time window (say 500 µs) into one super-batched Redis call.

- **Win mechanism**: amortise round-trip across many requests
- **Cost**: complex coordination, adds 0-500µs to each request to wait for
  the batch window to fill
- **Verdict: skip.** Fits HFT (µs-scale) much better than RTB (ms-scale).
  Adding 500µs of intentional batching delay to save ~1ms is not a clear win.

### Lua scripts for compute on Redis side

Push computation that needs multiple Redis lookups into a Lua script that
runs inside Redis (atomic, single round-trip).

- We already do this for `recordImpression` (atomic INCR + EXPIRE)
- Could potentially do freq-cap filtering server-side: send userId + list
  of (campaignId, max), get back filtered list of allowed campaignIds
- **Win mechanism**: one round-trip vs N (we already have MGET for that)
- **Drawback**: shifts CPU load from bidder to Redis (single-threaded
  bottleneck); Lua-in-Redis is also notorious for blocking the Redis event
  loop on long scripts
- **Verdict: skip for the current case.** MGET already collapses to one
  round-trip; Lua wouldn't add throughput. Reconsider if we ever need
  conditional logic across multiple Redis structures atomically.

### Hash data structure to reduce key count

Replace `freq:userId:campaignId` per-key with `HSET freq:userId campaignId N` —
one Redis hash per user, all freq counts inside.

- **Win mechanism**: one HGETALL (or HMGET) per bid instead of MGET on N keys
- **Drawback**: TTL is per-hash not per-field — lose the per-key 1-hour
  expiry behaviour
- **Verdict: skip.** TTL semantics matter for freq capping; loses fundamental
  feature.

### Sharded Redis

Multiple Redis instances by user_id hash; each bidder talks to all shards.

- **Win mechanism**: parallelism across shards if Redis CPU is the bottleneck
- **Drawback**: adds operational complexity, fan-out latency, partial-failure
  semantics
- **Verdict: skip until Redis CPU is actually saturated.** Server-side Redis
  has never been the bottleneck in any of our runs.

### Out-of-process model serving (Moloco pattern)

Move ML scoring to a separate gRPC service running on dedicated VMs. Bidder
calls it for predictions only, doesn't host the model itself.

- **Verdict: not relevant for default `FeatureWeightedScorer`** but **pursue
  if/when ML scoring at scale becomes the bottleneck.** Moloco's >5M QPS
  is partly enabled by this pattern.

---

## 4. JVM, GC & memory

### ZGC tuning

Already covered in [notes-perf-improvements.md §2](notes-perf-improvements.md).
Headlines: heap 512 MB → 2 GB, `-XX:SoftMaxHeapSize=1500m`, generational ZGC
already default in JDK 25.

- **Verdict: pursue.** Cheap quick win.

### `-XX:+UseLargePages` / Transparent Huge Pages

Reduce TLB miss rate by mapping the heap with 2 MB pages instead of 4 KB.

- **Win mechanism**: fewer TLB misses → less CPU stalled on memory access
- **Cost**: needs OS-level support; macOS has limited huge-page support;
  Linux production VMs typically need `madvise` mode for THP
- **Verdict: pursue in production deployment**, skip for laptop dev. THP
  is a Linux production-tuning concern.

### `-XX:+AlwaysPreTouch`

Already enabled. Documented in
[notes-perf-concepts.md](notes-perf-concepts.md).

- **Verdict: already done.**

### String deduplication (`-XX:+UseStringDeduplication`)

ZGC-compatible. Saves heap for repeated strings (e.g., common segment names).

- **Win mechanism**: lower heap usage → less GC frequency
- **Cost**: small CPU cost during marking phase
- **Verdict: pursue** as a low-risk JVM flag once we go to 2 GB heap. Likely
  a few-percent improvement; free.

### AppCDS (Application Class Data Sharing)

Saves class metadata to a shared archive at startup. Reduces startup time
~33-50% per [Red Hat](https://developers.redhat.com/articles/2024/01/23/speed-java-application-startup-time-appcds).

- **Win mechanism**: faster cold start
- **Drawback**: zero benefit at steady state — bidder runs warm 24/7
- **Verdict: skip for peak-throughput goal.** Pursue only when we care about
  pod cold-start (k8s rollouts, autoscaling triggers).

### Off-heap data structures (Chronicle Map, Map-DB, Infinispan)

Move the segment cache off-heap. Eliminates GC pressure entirely for that
data.

- **Win mechanism**: no GC scan over the cache; can be larger than heap
- **Cost**: serialization overhead per access; harder debugging; more native
  memory tracking concerns
- **Verdict: skip.** Caffeine's GC pressure is already negligible
  ([Run 3 H.4 GC analysis](LOAD-TEST-RESULTS-v2.md)). Solving a problem we
  don't have.

### Compact strings (`-XX:+CompactStrings`)

Already default in JDK 9+. Stores Latin-1 strings as `byte[]` instead of
`char[]`.

- **Verdict: already done.**

### Object pooling (BidContextPool pattern, extended)

Already pool `BidContext`. Could extend to:
- `AdCandidate` list (currently allocated per request)
- `Set<String>` for segments (currently from cache, fine as-is)
- Response objects (already pooled via codec design)

- **Verdict: consider** for `AdCandidate` if profiling shows it as an
  allocation hotspot. Otherwise skip — not currently a hotspot.

### JIT compilation thresholds

`-XX:CompileThreshold=N` and tiered-compilation threshold tuning. Defaults
are fine for steady-state services.

- **Verdict: skip.** Rare to beat the defaults; risk of regression.

---

## 5. JIT, AOT & runtime

### GraalVM JIT (vs HotSpot C2)

GraalVM as a JIT (replacing C2) typically gives 5-15% throughput on object-
heavy code. Available via `-XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler`
on supported JVMs.

- **Win mechanism**: more aggressive escape analysis, better inlining decisions
- **Cost**: longer JIT compilation time; more memory for JIT
- **Verdict: consider as a low-risk experiment** once Round 1 improvements
  land. JMH benchmark before/after on the bid pipeline; ship if measurable
  win.

### GraalVM native-image (AOT)

Already analysed via research: **0.8× peak throughput vs HotSpot C2**, 30×
faster startup, 2.5× less memory.

- **Verdict: skip.** AOT trades the wrong thing for our workload.

### PGO (Profile-Guided Optimization)

GraalVM Enterprise feature; record an execution profile, rebuild AOT binary
with that profile baked in.

- **Verdict: skip.** Tied to native-image which we just rejected.

### `-XX:+UseTransparentHugePages`

Same idea as `+UseLargePages` but using THP automatically.

- **Verdict: pursue in Linux production.** Already noted under JVM section.

### Project Leyden (when available)

Future Java feature aimed at AOT compilation done right. Not stable yet.

- **Verdict: skip until stable.**

### `-XX:+EnableJVMCI` + custom compilation

Pluggable compiler API. Mostly research-grade.

- **Verdict: skip.**

---

## 6. Concurrency primitives

### LMAX Disruptor for the in-process auction stage

Lock-free ring buffer for staged producer/consumer pipelines.

- **Throughput**: 25M+ msgs/sec single-writer; 52ns hop vs 32µs for ArrayBlockingQueue
- **Fit for our case**: would only matter if we had a separate in-process
  matching/auction stage decoupled from network I/O. Vert.x event loops
  already provide single-writer queues for the request path.
- **Verdict: skip for the HTTP path.** Reconsider only if we add a separate
  in-process auction or analytics pipeline.

### LongAdder vs AtomicLong for high-contention counters

`AtomicLong` uses CAS on a single memory location → contention under high
concurrent updates. `LongAdder` stripes across cells, reduces contention.

- We use `AtomicLong` in `BidContextPool`, freq counters, metrics
- **Verdict: pursue** for any counter that's incremented per request from
  many threads. `BidContextPool.totalCreated` and `currentSize` are
  candidates. Likely small win (a few %) but free.

### `@Contended` for cache-line padding

Annotates a field to ensure it's on its own cache line, eliminating false
sharing.

- Useful for hot per-thread or per-core counters
- Requires `-XX:-RestrictContended` in newer JDKs
- **Verdict: pursue** for hot counters that show contention in profiling.
  Our `BidContextPool` size atomics are the main candidates.

### Lock-free data structures

We already use `ConcurrentLinkedQueue`, `ConcurrentHashMap`, `AtomicInteger`.

- **Verdict: already aligned.** Audit for any `synchronized` blocks on the
  hot path; should be none.

### Striped locks (vs single locks)

Already used inside `ConcurrentHashMap`. Don't have application-level locks.

- **Verdict: already done by JDK.**

### Virtual threads (Project Loom) for the worker pool

Already analysed: anti-pattern on Netty event loops; JVM-Java/Quarkus/
Micronaut all flag the integration as experimental.

- **Verdict: skip.** Wrong fit for a sub-50ms async server.

### `Thread.onSpinWait()` busy-spin

HFT pattern. Spin instead of park when waiting briefly.

- **Win**: ~hundreds-of-ns latency savings
- **Cost**: burns a CPU core fully; only viable when you have isolated cores
  and few in-flight requests
- **Verdict: skip.** RTB has thousands of concurrent in-flight requests;
  busy-spin would burn cores rather than yield. Pattern fits HFT (one hot
  thread per pinned core), not us.

---

## 7. Network & IO tuning

### Netty pooled direct buffers + `numDirectArenas`

Already covered in [notes-perf-improvements.md §5](notes-perf-improvements.md).
Two JVM flags, 3-8% throughput.

- **Verdict: pursue.**

### HTTP/2 prior-knowledge (cleartext H2)

Vert.x supports H2 over plain TCP. Multiplexing without TLS overhead.

- **Verdict: consider** if exchanges support it. Defaults to HTTP/1.1; would
  need `HttpServerOptions.setUseAlpn(true)` and TLS for proper H2.

### TCP_NODELAY

Disable Nagle's algorithm. Already on by default for HTTP servers in Vert.x.

- **Verdict: already done.**

### SO_REUSEPORT

Multiple sockets on same port — load distributed by kernel. Vert.x enables
this automatically when you deploy multiple HttpServer verticles (which we do).

- **Verdict: already done.**

### Linux kernel sysctls (`net.core.rmem_max`, `wmem_max`, `somaxconn`)

Tune kernel TCP buffers and socket accept backlog.

- **Verdict: pursue in Linux production**, skip on macOS dev.

### Connection keep-alive tuning

Already on by default. `Vert.x HttpServerOptions.setIdleTimeout()` controls
when idle connections close.

- **Verdict: already done.** Consider tuning idle timeout for very-bursty
  traffic patterns.

---

## 8. OS & hardware tuning

### CPU pinning (taskset / numactl)

Pin Vert.x event-loop threads to specific cores; avoid OS scheduler moving
them around.

- **Win mechanism**: better L1/L2 cache hit rate, less context-switch overhead
- **Cost**: requires Linux production environment; pinning script in deploy
- **Verdict: pursue in Linux production** for very high RPS. Marginal at our
  current scale.

### NUMA awareness

For multi-socket servers, ensure threads access local memory. Use
`numactl --interleave=all` or `--cpubind`.

- **Verdict: pursue in production** if deploying to NUMA hardware
  (most server-class machines). M5 Pro is single-socket so N/A here.

### IRQ affinity

Pin network interrupts to specific cores.

- **Verdict: pursue in Linux production** for sub-100µs work; overkill at
  our scale.

### Power saving disabled

Set CPU governor to `performance`, disable C-states. Avoids latency spikes
from CPU sleep states.

- **Verdict: production-deploy concern.** Should be standard for production
  bidder hosts; not laptop concern.

### Hyperthreading on/off

Some HFT workloads disable HT to ensure each thread gets a full core.
RTB workloads usually benefit from HT (more throughput, less hot-path).

- **Verdict: skip — keep HT on for RTB.**

---

## 9. Algorithms & data structures

### Roaring Bitmaps for inverted-index segment matching

Currently we iterate all 1000 campaigns and check segment overlap per user.
With Roaring Bitmaps, we'd:

1. Pre-build `Map<segmentId, RoaringBitmap of campaignIds>` at startup
2. For each user with segments {S1, S2, ..., Sn}, OR the bitmaps for each
   segment to get the matching-campaign set
3. Result: O(segments) work instead of O(campaigns)

- **Win mechanism**: scales with **catalog size**, not segment count. At
  10K campaigns this is ~10× faster than linear scan. At 1K it's marginal.
- Roaring is "orders of magnitude faster" than HashSet on dense sets per
  the [Roaring paper](https://arxiv.org/pdf/1402.6407).
- **Verdict: pursue** when catalog grows past ~5K campaigns. At our current
  1000 the overhead might be a wash.

### Bloom filters for negative checks

For "is this user definitely not in segment X?" — Bloom filter says yes/maybe.
False positives are filtered out by real lookup.

- **Verdict: skip.** Caffeine cache already gives O(1) lookup; Bloom filter
  adds layer with marginal benefit.

### Better hash functions (xxHash, MurmurHash3)

`String.hashCode()` is fine for small string hashing. xxHash beats it on
larger payloads.

- **Verdict: skip for our current scale.** Not in the hot path.

### Trie / radix tree for prefix-matched targeting

Useful for targeting by URL prefix, geo prefix, etc. We don't currently
do prefix targeting.

- **Verdict: skip.** Not used by our targeting engine.

### Sorted arrays vs HashMaps for small N

For N < ~16, sorted array + binary search beats HashMap due to cache locality.

- Could apply to candidate lists (often < 100, sometimes ~3-5)
- **Verdict: consider** for the candidate-set inner loops if profiling
  shows hash overhead. Not currently visible.

### Pre-sized collections

`new ArrayList<>(expectedSize)` to avoid resize-reallocate.

- We do this already in places (`new ArrayList<>(allowedIds.size())` in
  FrequencyCapStage)
- **Verdict: audit but mostly already done.**

### Avoid boxing

`int` not `Integer`, `long[]` not `List<Long>`, etc.

- **Verdict: audit.** A grep for boxed types on the hot path would be a
  good 30-min exercise.

---

## 10. Library & framework alternatives

### Helidon Nima (virtual-threads-per-request)

Loom-based blocking-style HTTP. Tapir benchmarks show it's competitive
with Netty on GETs and lighter payloads.

- **Verdict: skip.** Not a clear win over Vert.x async; would mean rewriting
  the server layer to test. Vert.x's reactive design already fits sub-50ms
  SLA work better than virtual-thread imperative.

### Aeron messaging

Sub-microsecond IPC, >1M msgs/s/core.

- Wrong fit for HTTP request/response model
- Could fit inter-service bidder ↔ pricing-service communication if we
  ever build that
- **Verdict: skip for HTTP path**, consider for internal IPC only if we
  build that.

### Micronaut / Quarkus

Both target faster startup, lower memory than Spring. At runtime
performance is comparable to Vert.x for I/O-bound work.

- **Verdict: skip.** No clear runtime win; rewriting cost unjustified.

### Netty directly (drop Vert.x abstraction)

Pure Netty on-top of NIO. Removes a thin Vert.x layer.

- **Win mechanism**: maybe 2-5% throughput from dropping Vert.x overhead
- **Cost**: lose Vert.x's verticle abstraction, future async API support
- **Verdict: skip.** Vert.x overhead is small and the abstraction is worth it.

---

## 11. HFT-inspired patterns

### Single-writer principle

Each piece of mutable state has exactly one writer thread. Eliminates
contention and ordering concerns.

- We already follow this implicitly (Vert.x event loops are single-threaded
  per verticle)
- **Verdict: already aligned.** Audit for any cross-thread mutation.

### Cache-line padding (`@Contended`)

Already covered above. Pursue for hot counters.

### Off-heap arenas (Chronicle Map, Wire, Queue)

Eliminate GC entirely for hot data. Useful at µs scale where GC
predictability dominates.

- **Verdict: skip at ms scale.** ZGC sub-millisecond pauses are already
  invisible in 50ms SLA.

### Pinned thread per core

Each event-loop thread on its own pinned core, busy-spinning.

- **Verdict: skip.** RTB has thousands of in-flight requests; busy-spin
  burns cores.

### Avoiding megamorphic call sites

Hot virtual calls with many implementations slow down JIT inlining.

- **How**: use `final` classes, avoid deep inheritance, prefer composition
  over inheritance for hot path
- We already use `final` on PipelineStage implementations
- **Verdict: pursue audit.** Quick check for accidental megamorphism.

### Data-oriented design

Struct-of-arrays instead of array-of-structs. Better cache behaviour.

- Java doesn't have value types yet (waiting for Project Valhalla)
- **Verdict: skip until Valhalla**, when struct-like value classes land.

### Flame-graph-driven optimization

Profile, find hot paths, optimize ruthlessly.

- **Verdict: pursue continuously.** This is the meta-technique.

---

## 12. Architectural patterns

### Out-of-process ML scoring (Moloco pattern)

Move ML inference out to a TensorFlow Serving or Triton instance over gRPC.
Bidder calls it for predictions.

- **Win**: the bidder process never blocks on model inference; can scale
  ML and bidder separately
- **Cost**: network hop adds latency; complex deploy; circuit-break-able
- **Verdict: pursue if/when ML scoring is enabled in production at high
  RPS.** Default `FeatureWeightedScorer` doesn't need it.

### Pre-computed segment-to-campaign indexes

Build the inverted index (segment → list of campaigns targeting it) at
startup. Refresh on campaign updates. Replaces linear scan of 1000
campaigns per bid.

- **Win mechanism**: O(segments) instead of O(campaigns) per bid
- **Cost**: index needs to refresh when campaigns change (currently they
  don't refresh during process lifetime — known gap)
- **Verdict: pursue alongside the campaign-refresh fix.** Two improvements
  that pair naturally.

### Read replicas for Redis

Direct reads to a follower instance; writes to leader.

- **Verdict: skip until Redis is the bottleneck.** Server-side Redis has
  never been our bottleneck.

### Sharding by user_id at app level

Multiple bidder instances each handling a slice of the user keyspace.
Effectively horizontal scale + user-affinity routing.

- **Verdict: production scaling concern, not single-instance optimisation.**

### Campaign affinity routing

Each bidder instance specialises in a subset of campaigns; load balancer
routes by campaign-set. Less per-request work.

- **Verdict: skip.** Adds operational complexity; horizontal-scale of
  generic instances is simpler.

---

## 13. Profiling & verification

These aren't optimizations themselves — they're how you find what to
optimize next. **Always do these before guessing.**

### Java Flight Recorder (JFR)

Built into the JVM. Continuous low-overhead profiling.

```bash
# Enable in JVM_PROD:
-XX:+FlightRecorder
-XX:StartFlightRecording=duration=60s,filename=results/flight.jfr,settings=profile
```

- **Verdict: pursue.** Free, low overhead, incredibly useful. Should be
  first thing we do before any further optimisation work.

### async-profiler

Lower-overhead than JFR for CPU/wall-clock profiling. Generates flame graphs.

```bash
# Sample: java -agentpath:/path/to/libasyncProfiler.so=start,event=cpu,file=profile.html ...
```

- **Verdict: pursue.** Best tool for finding actual hot paths.

### JMH benchmarks for hot paths

Microbenchmarks for the per-bid pipeline, scoring, freq-cap MGET etc.

- **Verdict: pursue.** Measure each Round-1 improvement in isolation
  before/after.

### Continuous profiling in production

Tools like Pyroscope, Datadog Continuous Profiler, or async-profiler
running on a sample.

- **Verdict: pursue when production-deployed.** Always-on profiling is
  cheap and catches regressions.

---

## Prioritised cross-doc summary

Combining this brainstorm with [notes-perf-improvements.md](notes-perf-improvements.md):

### Tier 1 — definitely pursue (high ROI, low risk)

1. **Lettuce sync → async API** ([improvements §1](notes-perf-improvements.md))
2. **JVM heap 512 MB → 2 GB + ZGC tuning** ([improvements §2](notes-perf-improvements.md))
3. **Netty pooled direct buffers + arenas** ([improvements §5](notes-perf-improvements.md))
4. **`-XX:+UseStringDeduplication`** (this doc §4) — free
5. **JFR + async-profiler enabled** (this doc §13) — should be first work

Round 1 total effort: ~2 days. Expected: 5K → 25-30K RPS knee.

### Tier 2 — pursue if Tier 1 isn't enough

6. **Async pipeline** ([improvements §3](notes-perf-improvements.md)) — needs OTel tracing first
7. **DSL-JSON replacing Jackson on the hot path** (this doc §2)
8. **GraalVM JIT (HotSpot replacement)** (this doc §5) — JMH first
9. **`@Contended` on hot atomic counters** (this doc §6)
10. **LongAdder for high-contention counters** (this doc §6)
11. **Audit hot path for megamorphic call sites + boxing** (this doc §9, §11)
12. **Pre-computed inverted segment-to-campaign index** (this doc §12)

### Tier 3 — when ML scoring is in production

13. **Vectorised / batched scorer** ([improvements §4](notes-perf-improvements.md))
14. **Out-of-process ML serving over gRPC** (this doc §12)

### Tier 4 — production-deploy time, not now

15. **Linux production tuning**: huge pages, sysctls, CPU pinning,
    NUMA, IRQ affinity, power governor (this doc §7, §8)
16. **HTTP/2 prior-knowledge** if exchange supports (this doc §7)

### Skip — explicitly rejected with cited reasons

- Connection pooling (Lettuce docs against)
- Virtual threads on Netty event loops (anti-pattern)
- GraalVM native-image AOT (slower at peak)
- Disruptor for HTTP path (wrong fit)
- Aeron for HTTP path (wrong fit)
- simdjson-java (DOM API wrong fit)
- Off-heap caches (no GC problem to solve)
- Busy-spin patterns (RTB has too many in-flight requests)
- Sharded Redis (server-side never bottlenecked)
- Custom Netty (Vert.x overhead is small)

---

## What this enables for the Rust/C++ comparison phase

Once Tier 1 + Tier 2 are done, the Java version represents:

- **Best-effort idiomatic-Java performance** with industry-standard
  techniques applied
- **Defensible per-pod throughput** (target: 25-50K RPS, matching the
  RTB4FREE-on-Java-8 benchmark with modern stack)
- **Honest measurement infrastructure** (JFR + async-profiler + JMH)

Then a clean Rust port (e.g. Tokio + redis-rs + simd-json) and a clean C++
port (e.g. Seastar + hiredis + simdjson) can be compared **like-for-like**:

- Same workload (same k6 scripts, same data, same SLA)
- Same dependencies (same Redis, same Postgres, same Kafka)
- Different language/runtime — pure raw-language comparison

If the language clones beat the optimised Java by >2×, that's the
language overhead being measured. If they're within 30%, the bidder
problem is **bound by I/O and algorithms**, not language — which is the
expected finding for ms-scale ad-tech work.

This document sets up that comparison fairly: by extracting every
defensible Java optimisation first, we measure raw language difference,
not "optimised C++ vs naive Java" (which is a strawman comparison
common in this space).

---

## Sources

All claims with numbers in this document are from these sources:

- [RTB4FREE bidder README](https://github.com/RTB4FREE/bidder/blob/master/README.md)
- [Moloco DSP architecture](https://www.moloco.com/r-d-blog/challenges-in-building-a-scalable-demand-side-platform-dsp-service)
- [Moloco + Bigtable](https://cloud.google.com/blog/products/databases/moloco-uses-cloud-bigtable-to-build-their-ad-tech-platform)
- [LMAX Disruptor performance](https://github.com/LMAX-Exchange/disruptor/wiki/Performance-Results)
- [LMAX Architecture (Fowler)](https://martinfowler.com/articles/lmax.html)
- [Aeron benchmarks](https://github.com/aeron-io/aeron/wiki/Performance-Testing)
- [simdjson-java](https://github.com/simdjson/simdjson-java)
- [Java JSON benchmark](https://github.com/fabienrenaud/java-json-benchmark)
- [DSL-JSON](https://github.com/ngs-doo/dsl-json)
- [Roaring Bitmap repo](https://github.com/RoaringBitmap/RoaringBitmap)
- [Roaring Bitmap paper](https://arxiv.org/pdf/1402.6407)
- [Red Hat AppCDS](https://developers.redhat.com/articles/2024/01/23/speed-java-application-startup-time-appcds)
- [GraalVM native-image perf](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/)
- [SoftwareMill — Tapir/Nima benchmark](https://softwaremill.com/benchmarking-tapir-part-3-loom/)
- [Lettuce Connection Pooling](https://github.com/redis/lettuce/wiki/Connection-Pooling-5.1)
- [Lettuce Pipelining](https://github.com/lettuce-io/lettuce-core/wiki/Pipelining-and-command-flushing)
- [AWS — ElastiCache Lettuce tuning](https://aws.amazon.com/blogs/database/optimize-redis-client-performance-for-amazon-elasticache/)
- [HFT low-latency best practices](https://electronictradinghub.com/best-practices-on-hft-low-latency-software/)
- [ZGC vs Shenandoah](https://www.javacodegeeks.com/2025/04/zgc-vs-shenandoah-ultra-low-latency-gc-for-java.html)
