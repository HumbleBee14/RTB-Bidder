# Phase 8: Kafka Events — Full Ad Event Lifecycle

## What was built

Complete ad event lifecycle: every bid, win notification, impression, and click publishes to Kafka asynchronously. Async, fire-and-forget — never blocks the bid path. Two implementations: KafkaEventPublisher (production) and NoOpEventPublisher (development without Kafka).

## Why Kafka for ad events

The bidder processes 50K+ requests/second. Each request generates events (bid, win, impression, click) that multiple downstream systems need:

| Consumer | What it needs | Why |
|----------|-------------|-----|
| **Billing system** | WinEvents | Charge the advertiser based on clearing price |
| **Analytics (ClickHouse)** | All events | Dashboards: fill rate, CTR, win rate, revenue |
| **ML pipeline** | Impression + Click events | Retrain pCTR model on real user behavior |
| **Campaign management** | Bid + Win events | Budget burn rate, pacing adjustments |
| **Fraud detection** | Impression + Click events | Detect click fraud, bot traffic |

Without a message queue, each consumer would need its own HTTP endpoint on the bidder — tight coupling, no replay, no buffering. Kafka decouples producers from consumers:

```
Bidder → Kafka topics → N consumers read independently
                      → each at their own pace
                      → can replay from any offset
                      → bidder doesn't know or care who's consuming
```

**Why Kafka specifically** (vs Redis Streams, RabbitMQ, etc.):
- **Throughput**: Kafka handles 1M+ messages/sec per partition — designed for event streaming at scale
- **Durability**: events persist on disk — consumers can replay from any point
- **Ordering**: events within a partition are strictly ordered (bid → win → impression → click for the same bidId hash to the same partition)
- **Industry standard**: Moloco, Criteo, The Trade Desk all use Kafka for ad event pipelines

## The Ad Event Lifecycle

```
1. Exchange sends bid request    → POST /bid        → BidEvent to Kafka
   (we respond with bid or 204)                       (bid=true/false, slots, latency)

2. Exchange picks winner         → POST /win         → WinEvent to Kafka
   (tells us we won the auction)                      (BILLING happens here)

3. User's device loads the ad    → GET /impression   → ImpressionEvent to Kafka
   (ad was actually rendered)                          (confirmed display)

4. User clicks the ad            → GET /click        → ClickEvent to Kafka
   (user engaged with the ad)                          (CPC billing trigger)
```

Without this lifecycle:
- **No billing** — you don't know what to charge (win event = actual clearing price)
- **No attribution** — you can't tell which ads drove clicks/conversions
- **No campaign measurement** — fill rate, CTR, win rate are all derived from these events
- **No ML training data** — the pCTR model (Phase 6.5) needs impression+click data to train

## Architecture

```
  BidRequestHandler                    WinHandler              TrackingHandler
       │                                  │                    │           │
  response sent                     response sent        pixel sent   click ack
       │                                  │                    │           │
       ▼ (async, non-blocking)            ▼                    ▼           ▼
  eventPublisher.publishBid()    publishWin()         publishImpression() publishClick()
       │                                  │                    │           │
       ▼                                  ▼                    ▼           ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                     Kafka (async producer)                              │
  │  bid-events  │  win-events  │  impression-events  │  click-events       │
  └─────────────────────────────────────────────────────────────────────────┘
       │                                                          │
       ▼                                                          ▼
  Analytics (ClickHouse)                              ML training pipeline
  Billing system                                      pCTR model retraining
  Campaign dashboards                                 Feature store update
```

Events are published AFTER the HTTP response is sent. The Kafka producer is async with fire-and-forget semantics — the bid path never waits for Kafka ack.

## Kafka Producer Configuration

| Setting | Value | Why |
|---------|-------|-----|
| `acks` | `1` (leader only) | Fire-and-forget — we don't wait for replica ack |
| `batch.size` | `16384` | Batch events for throughput (events are post-response, not latency-sensitive) |
| `linger.ms` | `10` | Wait 10ms to fill batch before sending |
| `compression` | `lz4` | Compress batches — lower network I/O, fast decompression |

## Event Models

### BidEvent
```json
{
  "requestId": "uuid",
  "userId": "user_00042",
  "bid": true,
  "noBidReason": null,
  "slotBids": [{"slotId": "s1", "campaignId": "camp-008", "price": 0.60}],
  "pipelineLatencyMs": 14,
  "timestamp": "2026-04-20T05:13:30.000Z"
}
```
Published for EVERY request — bid and no-bid. No-bid events include the reason (NO_MATCHING_CAMPAIGN, TIMEOUT, etc.).

### WinEvent
```json
{
  "bidId": "kafka-test-bid",
  "campaignId": "camp-008",
  "clearingPrice": 0.50,
  "timestamp": "2026-04-20T05:13:35.008Z"
}
```
Published when the exchange tells us our bid won. `clearingPrice` may differ from our bid price (second-price auction).

### ImpressionEvent / ClickEvent
```json
{"bidId": "kafka-test-bid", "timestamp": "2026-04-20T05:13:35.407Z"}
```

## Performance Decisions and Reasoning

### Why event publishing is offloaded to a background thread

Vert.x uses a single event loop thread for ALL HTTP requests. If anything blocks this thread, every request queues up. Kafka's `producer.send()` is "async" but can block in two scenarios:

1. **Buffer full** — producer has a 32MB buffer. At 50K events/sec, if Kafka is slow, `send()` blocks up to `max.block.ms` (5s) waiting for buffer space
2. **Metadata fetch** — first send to a new topic blocks while Kafka returns partition metadata

Even `objectMapper.writeValueAsString()` is synchronous CPU work (~0.1ms) on the caller thread.

**Solution**: all event publishing runs on a dedicated single-threaded `ExecutorService`:

```java
// Event loop thread (handles HTTP):
eventPublisher.publishBid(event);           // ← returns immediately (submits to executor)

// Background thread (handles Kafka):
executor.submit(() -> publish(topic, key, event));  // ← serialization + send happen here
```

The event loop thread never touches Kafka. Zero blocking risk regardless of Kafka health, buffer state, or serialization cost. The background thread is single-threaded (not a pool) to preserve event ordering and avoid thread contention on the Kafka producer.

### General principle: NOTHING blocks the event loop after response.end()

Frequency capper's `recordImpression()` calls Redis via sync Lua script. Even though it runs "after" `response.end()`, it still runs ON the event loop thread. Vert.x sends the HTTP response but the thread is still occupied — if Redis takes 5ms, the next incoming request waits 5ms.

**Fix**: ALL post-response work runs on a dedicated background thread:

```java
ctx.response().end(responseJson);              // ← event loop sends response, returns
postResponseExecutor.submit(() -> {            // ← background thread handles everything else
    frequencyCapper.recordImpression(...);     // sync Redis — blocks background thread, not event loop
    eventPublisher.publishBid(...);            // submits to Kafka executor
});
```

**This pattern applies everywhere**: any I/O that happens after the response (Redis writes, Kafka publishes, metric updates) must be offloaded. The event loop thread should do ONE thing: receive request → pipeline → send response → return immediately.

### Why events are published AFTER response.end(), not before

```java
ctx.response().end(responseJson);     // ← response sent to exchange
eventPublisher.publishBid(...);       // ← event published AFTER
```

The exchange has a hard SLA (100-200ms). Every millisecond before `response.end()` counts. Event publishing involves JSON serialization (~0.1ms) and Kafka buffer enqueue (~0.01ms). By publishing AFTER the response, bid latency is completely unaffected by Kafka health.

### Why UUID.randomUUID() for requestId is acceptable

`UUID.randomUUID()` costs ~1 microsecond per call. At 50K QPS = 50ms/second total CPU — negligible.

| Approach | Latency | Uniqueness | Verdict |
|----------|---------|-----------|---------|
| `UUID.randomUUID()` | ~1μs | Globally unique | Chosen — simple, no coordination across instances |
| `AtomicLong` counter | ~0.01μs | Per-JVM only | Not unique across multiple bidder instances |
| Timestamp + random | ~0.5μs | Probably unique | Collision risk under concurrency |

The requestId links bid → win → impression → click events in analytics. It must be globally unique.

### Why KafkaEventPublisher has its own ObjectMapper

HTTP API uses `snake_case` (OpenRTB convention for exchange). Kafka events use `camelCase` (Java convention for internal systems). The Kafka ObjectMapper also needs `JavaTimeModule` for `Instant` serialization. Separate ObjectMappers = separate serialization contracts.

### Why max.block.ms=5000 (not default 60s)

`new KafkaProducer(props)` blocks for metadata fetch. Default 60s timeout means: Kafka unreachable → server hangs 60s at startup → K8s kills pod → restart loop. With 5s: fast fail → start with degraded events → recover when Kafka is back.

### Why acks=1, not acks=all

| acks | Durability | Latency | Use case |
|------|-----------|---------|----------|
| `0` | None | Fastest | Too risky |
| `1` | Leader ack | ~1ms | Bid/impression/click events (analytics) |
| `all` | All replicas | ~5-10ms | WinEvents (billing) — TODO in Phase 10 |

Lost bid events = analytics gap (tolerable). Lost win events = lost revenue tracking (not tolerable). Phase 10 upgrades WinEvents to `acks=all` with retries.

### Why batch + linger + compression

```
Without batching: 50K events/sec × 1 network call = 50K syscalls/sec
With batching:    50K events/sec ÷ ~100/batch = 500 syscalls/sec (100x reduction)
```

- `batch.size=16384`: fill 16KB before sending
- `linger.ms=10`: wait 10ms to fill batch (events are post-response, delay irrelevant)
- `compression=lz4`: JSON compresses 3-5x, reducing network I/O

### Why ImpressionEvent/ClickEvent are enriched with userId, campaignId

Thin events (just bidId) require JOINs in analytics:
```sql
-- Without enrichment: JOIN required (slow at scale)
SELECT count(clicks)/count(impressions) FROM impressions JOIN bids ON bid_id

-- With enrichment: direct aggregation (10-100x faster)
SELECT count(clicks)/count(impressions) FROM impressions WHERE campaign_id='camp-008'
```

Currently userId/campaignId are null in impression/click events (TODO: bid cache lookup). When populated, analytics queries skip expensive JOINs.

### TODO: Kafka failure resilience (Phase 10)

Current: dropped events on Kafka failure (error log only). Needed:
- Circuit breaker on publisher
- Fallback: write to local file, replay when Kafka recovers
- WinEvents: `acks=all` + retries (billing cannot lose events)
- Dead-letter topic for permanently failed events

## Files

| File | Purpose |
|------|---------|
| `event/EventPublisher.java` | Interface — publish 4 event types |
| `event/KafkaEventPublisher.java` | Async Kafka producer, fire-and-forget, batched |
| `event/NoOpEventPublisher.java` | Logs events at DEBUG level (dev without Kafka) |
| `event/events/BidEvent.java` | Bid/no-bid event with slot details |
| `event/events/WinEvent.java` | Win notification (billing trigger) |
| `event/events/ImpressionEvent.java` | Ad was rendered |
| `event/events/ClickEvent.java` | User clicked the ad |

## Config

```properties
# Development (default) — no Kafka needed
events.type=noop

# Production — publish to Kafka
events.type=kafka
kafka.bootstrap.servers=localhost:9092
kafka.topic.bid-events=bid-events
kafka.topic.win-events=win-events
kafka.topic.impression-events=impression-events
kafka.topic.click-events=click-events
```

## Test Results

Events verified in Kafka:

```
win-events:        {"bidId":"kafka-test-bid","campaignId":"camp-008","clearingPrice":0.5,"timestamp":"2026-04-20T05:13:35.008Z"}
impression-events: {"bidId":"kafka-test-bid","timestamp":"2026-04-20T05:13:35.407Z"}
click-events:      {"bidId":"kafka-test-bid","timestamp":"2026-04-20T05:13:35.746Z"}
```

## How to test

```bash
# Start Kafka
docker compose up -d redis kafka

# Build and run with Kafka events
mvnw.cmd package
EVENTS_TYPE=kafka java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Send full lifecycle
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30}]}"

curl -s -X POST http://localhost:8080/win -H "Content-Type: application/json" \
  -d "{\"bid_id\":\"test-123\",\"campaign_id\":\"camp-008\",\"clearing_price\":0.50}"

curl http://localhost:8080/impression?bid_id=test-123
curl http://localhost:8080/click?bid_id=test-123

# Verify events in Kafka
docker exec java-rtb-bidder-kafka-1 bash -c "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic win-events --from-beginning --timeout-ms 5000"
```

## Later enhancement: tracking URLs carry user/campaign/slot context

When Phase 8 was first built, the tracking endpoints only received `bid_id` as a query param — so `ImpressionEvent` and `ClickEvent` had `null` for `userId`, `campaignId`, and `slotId`. That made downstream analytics (e.g., CTR by campaign in ClickHouse) impossible.

Fix (Phase 15 cleanup): `ResponseBuildStage` now embeds all four IDs in the tracking URLs:

```
https://bidder.example.com/impression?bid_id=<uuid>&user_id=<id>&campaign_id=<id>&slot_id=<id>
https://bidder.example.com/click?bid_id=<uuid>&user_id=<id>&campaign_id=<id>&slot_id=<id>
```

`TrackingHandler` reads all four from the request params and publishes complete events.

Why query params and not a bid-cache lookup? A cache would require storing every bid_id → context mapping (Redis, ~1h TTL). At 50K RPS × 1h = 180M entries, plus an extra Redis hit on the tracking hot path. The URL-embedding approach has zero lookup cost — the exchange/browser passes the context back when it pings the URL. This is how real ad-tech does it (standard OpenRTB tracking URL pattern).
