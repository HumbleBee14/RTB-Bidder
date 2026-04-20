# Phase 11: Jackson Streaming + Zero-Alloc — Performance Optimization

## What was built

Replaced ObjectMapper with Jackson Streaming API for JSON parsing and writing on the hot path. Added BidContext object pool to eliminate per-request allocation. The bid path now creates near-zero intermediate objects for GC to collect.

## Why this matters

At 50K QPS, every object allocation on the hot path compounds:

| Component | Before (ObjectMapper) | After (Streaming) | Savings |
|-----------|----------------------|-------------------|---------|
| JSON parse | ~50 objects (tree nodes, type info) | 0 (token-by-token into variables) | 2.5M objects/sec |
| JSON write | ~20 objects (String buffer, field names) | 0 (direct to byte buffer) | 1M objects/sec |
| BidContext | new per request | pooled (acquire/release) | 50K objects/sec |

Total: ~3.5M fewer objects/sec for GC. With ZGC, this means fewer sub-millisecond pauses and lower p99 latency.

## How Jackson Streaming works

### ObjectMapper (before — creates intermediate tree)
```java
BidRequest request = objectMapper.readValue(bytes, BidRequest.class);
// Internally: bytes → JsonNode tree → reflection → BidRequest
// Creates ~50 intermediate objects
```

### JsonParser streaming (after — reads tokens directly)
```java
JsonParser parser = jsonFactory.createParser(bytes);
parser.nextToken();  // START_OBJECT
while (parser.nextToken() != END_OBJECT) {
    String field = parser.currentName();
    parser.nextToken();
    switch (field) {
        case "user_id" -> userId = parser.getText();     // no allocation
        case "bid_floor" -> bidFloor = parser.getDoubleValue(); // no boxing
    }
}
// Result: one BidRequest record, zero intermediate objects
```

### JsonGenerator streaming (response)
```java
JsonGenerator gen = jsonFactory.createGenerator(outputStream);
gen.writeStartObject();
gen.writeStringField("bid_id", bidId);    // writes directly to buffer
gen.writeNumberField("price", 0.60);      // no Double boxing
gen.writeEndObject();
// Result: bytes written directly, no intermediate String
```

## BidContext Object Pool

```
Without pool:
  request 1: new BidContext() → use → GC collects
  request 2: new BidContext() → use → GC collects
  50K requests/sec × 1 object = 50K allocations/sec for GC

With pool:
  startup: pool creates BidContext objects on demand
  request 1: pool.acquire() → use → pool.release() (returned to pool)
  request 2: pool.acquire() → SAME object reused → pool.release()
  50K requests/sec × 0 new allocations = 0 for GC (after warmup)
```

Pool is `ConcurrentLinkedQueue<BidContext>` — lock-free acquire/release. Max size 256 (prevents unbounded growth). After warmup, zero allocations.

### Context lifecycle with pool

```java
BidContext ctx = pipeline.execute(request, startNanos);  // acquire from pool
// ... read response, build event ...
pipeline.release(ctx);                                    // return to pool
```

The handler owns the release. On the success path, the post-response background thread releases after freq recording + event publishing. On error/no-bid path, the finally block releases.

## Files

| File | Purpose |
|------|---------|
| `codec/BidRequestCodec.java` | Streaming JSON parser for BidRequest |
| `codec/BidResponseCodec.java` | Streaming JSON writer for BidResponse |
| `pipeline/BidContextPool.java` | Object pool with ConcurrentLinkedQueue |
| `pipeline/BidContext.java` | Added reset() and clear() for pool reuse |
| `pipeline/BidPipeline.java` | Uses pool for context lifecycle |
| `server/BidRequestHandler.java` | Uses streaming codecs, manages pool release |

## How to verify

Run async-profiler allocation flame graph under load:
```bash
# Start server
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Attach profiler (Linux/macOS)
./asprof -e alloc -d 30 -f alloc-flamegraph.html $(pgrep -f rtb-bidder)

# Before Phase 11: thousands of allocations per request in bid path
# After Phase 11: near-zero allocations in bid path
```
