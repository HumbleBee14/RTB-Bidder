# Phase 2: Pipeline — BidContext flows through stages with SLA timeout

## What was built

Chain-of-responsibility pipeline with SLA deadline enforcement. Bid requests flow through ordered stages. If the pipeline exceeds the deadline, it aborts immediately with TIMEOUT — a late response is worse than no response.

## Request Flow (Phase 2)

```
BidRequestHandler (parse JSON)
  
  POST /bid arrives
       │
       ▼
  ┌─ BidPipeline ────────────────────────────────────────────────┐
  │  check deadline → RequestValidationStage                     │
  │  check deadline → ResponseBuildStage                         │
  │  (future stages plug in here without changing existing code) │
  └──────────────────────────────────────────────────────────────┘

```

```
  POST /bid arrives
       │
       ▼
  BidRequestHandler
       │
       ├─ parse JSON body → BidRequest
       │
       ├─ pipeline.execute(request, startNanos)
       │       │
       │       ▼
       │  ┌─ BidPipeline ──────────────────────────────────────────┐
       │  │                                                        │
       │  │  deadline exceeded? ──── YES ──→ abort(TIMEOUT)        │
       │  │       │ NO                                             │
       │  │       ▼                                                │
       │  │  RequestValidationStage                                │
       │  │       │                                                │
       │  │       ├─ user_id missing?  ──→ abort(NO_MATCHING)      │
       │  │       ├─ ad_slots empty?   ──→ abort(NO_MATCHING)      │
       │  │       ├─ bid_floor < 0?    ──→ abort(NO_MATCHING)      │
       │  │       │ OK                                             │
       │  │       ▼                                                │
       │  │  deadline exceeded? ──── YES ──→ abort(TIMEOUT)        │
       │  │       │ NO                                             │
       │  │       ▼                                                │
       │  │  ResponseBuildStage                                    │
       │  │       │                                                │
       │  │       └─ build BidResponse → ctx.setResponse()         │
       │  │                                                        │
       │  │  post-loop: deadline exceeded? ──→ abort(TIMEOUT)      │
       │  │  post-loop: response null?     ──→ abort(INTERNAL_ERR) │
       │  │                                                        │
       │  └──────────────────────────┬─────────────────────────────┘
       │                             │
       │       ◄─── BidContext ──────┘
       │
       ├─ ctx.isAborted()?
       │       │
       │       ├── YES → 204 No-bid + X-NoBid-Reason header
       │       │
       │       └── NO  → 200 + BidResponse JSON
       ▼
  Response sent
```

### How stages will grow (future phases)

```
  ┌─ BidPipeline ──────────────────────────────────────────────────┐
  │                                                                │
  │  1. RequestValidationStage      ← Phase 2 (done)              │
  │  2. UserEnrichmentStage         ← Phase 3 (Redis segments)    │
  │  3. CandidateRetrievalStage     ← Phase 4 (targeting engine)  │
  │  4. FrequencyCapStage           ← Phase 5 (Redis INCR+TTL)    │
  │  5. ScoringStage                ← Phase 6 (weighted formula)  │
  │  6. RankingStage                ← Phase 6 (sort, pick top-1)  │
  │  7. BudgetPacingStage           ← Phase 7 (atomic decrement)  │
  │  8. ResponseBuildStage          ← Phase 2 (done)              │
  │                                                                │
  │  Adding a stage = one new class + one line in Application.java │
  └────────────────────────────────────────────────────────────────┘
```

## Files

| File | Purpose |
|------|---------|
| `pipeline/PipelineStage.java` | Interface — `process(BidContext)` + `name()` |
| `pipeline/BidContext.java` | Mutable context — request, response, deadline, noBidReason |
| `pipeline/BidPipeline.java` | Orchestrator — runs stages, enforces SLA, logs timing |
| `pipeline/PipelineException.java` | Unchecked — caught by pipeline, triggers no-bid |
| `pipeline/stages/RequestValidationStage.java` | Validates user_id, ad_slots, bid_floor |
| `pipeline/stages/ResponseBuildStage.java` | Builds BidResponse from context |
| `config/PipelineConfig.java` | SLA config — `maxLatencyMs` from application.properties |

## Pipeline log output

Single summary line per request — every stage's latency, total time, and outcome:

```
Pipeline: [RequestValidation: 0.01ms, ResponseBuild: 0.05ms] total=0.28ms deadline=50ms bid=true
Pipeline: [RequestValidation: 0.01ms] total=0.28ms deadline=50ms bid=false
```

## Design Decisions

### SLA deadline is checked before AND after stages

The pipeline checks `System.nanoTime() > deadlineNanos` **before** each stage (don't start if no time left) and **after** the loop completes (catches a slow last stage that overran the deadline). This prevents emitting a late 200 response.

In production RTB, the exchange has a hard timeout (typically 100-200ms network-to-network). By the time our bidder sees the request, we've already burned ~50ms on network. Our internal SLA of 50ms means if we're late, the exchange has already picked another bidder. A late bid is wasted CPU — worse than no bid.

### First request TIMEOUT is expected (JVM warmup)

The very first request after cold start hit TIMEOUT (90ms > 50ms deadline) because of JVM class loading and JIT compilation. This is normal and expected. In production, we'll solve this with:
- Warm-up requests at deploy time (hit `/bid` a few times before accepting real traffic)
- K8s readiness probe that only passes after warmup

### Two ways to stop the pipeline: abort vs exception

- **`ctx.abort(reason)`** — expected no-bid decisions (invalid request, no matching campaign). Clean flow, no stack trace.
- **`PipelineException`** — unexpected failures (bugs, NPEs). Pipeline catches it, logs stack trace, sets `INTERNAL_ERROR`.

Both are unchecked. No `throws` clause on the interface — the pipeline catches everything.

### BidContext is mutable by design

Unlike the immutable model records (`BidRequest`, `BidResponse`), `BidContext` is intentionally mutable. Each stage reads and writes to it. This is the pipeline pattern — context accumulates state as it flows through stages. In Phase 11, this becomes object-pooled (acquire/release instead of new/GC).

## How to test

```bash
mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Warmup (first request may timeout due to JVM class loading)
curl -s -o /dev/null http://localhost:8080/health

# Valid bid — 200
curl -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"user_id\":\"u123\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"

# No-bid (missing user_id) — 204
curl -v -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"

# No-bid (negative bid_floor) — 204
curl -v -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"user_id\":\"u123\",\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":-1.0}]}"
```

Watch console for pipeline timing logs.
