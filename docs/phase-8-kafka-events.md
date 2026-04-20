# Phase 8: Kafka Events — Full Ad Event Lifecycle

## What was built

Complete ad event lifecycle: every bid, win notification, impression, and click publishes to Kafka. Async, fire-and-forget — never blocks the bid path. Two implementations: KafkaEventPublisher (production) and NoOpEventPublisher (development without Kafka).

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
  │  bid-events  │  win-events  │  impression-events  │  click-events      │
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
