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

## Optimization Details

### ThreadLocal ByteArrayOutputStream for response writing

`new ByteArrayOutputStream(512)` per response = 50K buffer allocations/sec. Fix: `ThreadLocal<ByteArrayOutputStream>` reused per thread, `reset()` between calls. After warmup, zero buffer allocations.

### AtomicInteger for pool size tracking (not queue.size())

`ConcurrentLinkedQueue.size()` is O(n) — it traverses the entire queue to count elements. At pool size 256, that's 256 pointer traversals per `release()` call. `AtomicInteger` counter is O(1) — one atomic read.

### Zero-copy buffer parsing (when possible)

Vert.x `Buffer.getBytes()` copies the byte array. When the underlying Netty `ByteBuf` has an array backing (`getByteBuf().array()`), we parse directly from it — zero copy. Falls back to `getBytes()` for direct (off-heap) buffers.

### What still allocates (and why)

BidRequest, App, Device, AdSlot records are still `new` per request. These are Java records (immutable value objects) — you can't pool them without making them mutable classes. The savings come from eliminating Jackson's ~50 internal tree nodes, not the domain objects. At 5-6 small records per request vs 50+ tree nodes, this is an acceptable trade-off.

## Early context release — don't hold pooled objects across threads

A subtle pool misuse: the post-response background thread originally held a reference to the `BidContext` until after frequency recording and event publishing were done. This means pooled objects live longer than necessary — and if the background thread is slow, the pool starves.

Fix: extract all data needed for post-response work (userId, winner IDs, slot bids) into local variables BEFORE releasing the context back to the pool. The background thread works with extracted data only — never touches the pooled object.

```java
// Extract data needed for post-response BEFORE releasing context to pool
String userId = request.userId();
List<String[]> winnerIds = bidCtx.getSlotWinners().values().stream()
        .map(w -> new String[]{userId, w.getCampaign().id()})
        .toList();

// Release context to pool immediately — don't hold it for post-response
pipeline.release(bidCtx);
bidCtx = null;

// Post-response work uses extracted data only — no context reference
postResponseExecutor.submit(() -> {
    for (String[] ids : winnerIds) {
        frequencyCapper.recordImpression(ids[0], ids[1]);
    }
    eventPublisher.publishBid(BidEvent.bid(requestId, userId, slotBids, latencyMs));
});
```

Rule: pooled objects should be held for the shortest possible time. If a background thread needs data from a pooled object, copy the data out first, then return the object.

## Streaming parser null safety — token type checks

Jackson Streaming parses token-by-token. When a JSON field has `null` as its value (e.g., `"app": null`), `parser.nextToken()` returns `VALUE_NULL`, not `START_OBJECT`. Calling `parseApp(parser)` on a null token would try to read fields from a non-object — corrupting the parser position for all subsequent fields.

```java
// Wrong — crashes or corrupts parser state if app is null
case "app" -> app = parseApp(parser);

// Correct — check token type before delegating to sub-parser
case "app" -> app = parser.currentToken() == JsonToken.START_OBJECT ? parseApp(parser) : null;
```

This matters because ObjectMapper handles this transparently (it builds a tree first), but streaming API gives you raw tokens — you must handle every token type yourself.

## Test results

```
Multi-slot bid (2 slots, streaming codec)    → HTTP 200, Nike 300x250 + HealthPlus 728x90
Missing app/device fields                     → HTTP 200, clean bid (null fields handled)
Explicit null app/device                      → HTTP 200, clean bid (token type check works)
Empty body                                    → HTTP 204, INTERNAL_ERROR (no crash)
Malformed JSON                                → HTTP 204, INTERNAL_ERROR (caught, logged)
Missing user_id                               → HTTP 204, NO_MATCHING_CAMPAIGN
Missing ad_slots                              → HTTP 204, NO_MATCHING_CAMPAIGN
Unknown user (no Redis segments)              → HTTP 204, NO_MATCHING_CAMPAIGN
Bid floor > all campaigns                     → HTTP 204, NO_MATCHING_CAMPAIGN (floor filter)
Health check                                  → HTTP 200, Redis UP, Kafka UP
```

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
