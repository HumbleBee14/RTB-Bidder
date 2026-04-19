# Phase 2: Pipeline — BidContext flows through stages with SLA timeout

## What was built

Chain-of-responsibility pipeline with SLA deadline enforcement. Bid requests flow through ordered stages. If the pipeline exceeds the deadline, it aborts immediately with TIMEOUT — a late response is worse than no response.

## Pipeline flow

```
BidRequestHandler (parse JSON)
       │
       ▼
  ┌─ BidPipeline ────────────────────────────────────────────────┐
  │  check deadline → RequestValidationStage                     │
  │  check deadline → ResponseBuildStage                         │
  │  (future stages plug in here without changing existing code) │
  └──────────────────────────────────────────────────────────────┘
       │
       ▼
  BidRequestHandler (200 + response OR 204 no-bid)
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

### SLA deadline is checked before each stage, not after

The pipeline checks `System.nanoTime() > deadlineNanos` **before** entering each stage. If time is up, it doesn't start the next stage — it aborts immediately. This prevents a slow stage from consuming time that could be used to return a faster no-bid.

In production RTB, the exchange has a hard timeout (typically 100-200ms network-to-network). By the time our bidder sees the request, we've already burned ~50ms on network. Our internal SLA of 50ms means if we're late, the exchange has already picked another bidder. A late bid is wasted CPU — worse than no bid.

### First request TIMEOUT is expected (JVM warmup)

The very first request after cold start hit TIMEOUT (90ms > 50ms deadline) because of JVM class loading and JIT compilation. This is normal and expected. In production, we'll solve this with:
- Warm-up requests at deploy time (hit `/bid` a few times before accepting real traffic)
- K8s readiness probe that only passes after warmup

### PipelineException is unchecked (not checked)

Stages throw `PipelineException` (extends RuntimeException) for failures like malformed data. The pipeline catches it, sets `INTERNAL_ERROR`, and moves on. No `throws` clutter on the hot path. Stage bugs crash loudly; stage decisions (no-bid) flow cleanly via `ctx.abort()`.

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

# No-bid (negative bid_floor → PipelineException) — 204
curl -v -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"user_id\":\"u123\",\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":-1.0}]}"
```

Watch console for pipeline timing logs.
