# Phase 10: Resilience — Circuit Breakers, Timeouts, Graceful Degradation

## What was built

Circuit breaker pattern on every external dependency (Redis, Kafka). When a dependency is slow or down, the bidder degrades gracefully — serving partial results instead of crashing. No cascading failure. No thread exhaustion. No 500 errors.

## Why this matters

Without circuit breakers, a slow Redis takes down the entire bidder:

```
Redis response time: 5ms → 500ms → 5000ms (degrading)
                                        ↓
Every bid request waits 5 seconds for Redis
                                        ↓
Vert.x event loop thread is blocked for 5s per request
                                        ↓
All other requests queue behind the blocked thread
                                        ↓
Exchange SLA (200ms) blown for EVERY request
                                        ↓
Exchange stops sending traffic → revenue = $0
```

With circuit breaker:

```
Redis response time: 5ms → 500ms → timeout after 50ms
                                        ↓
Circuit breaker: failure count 1... 2... 3... 4... 5
                                        ↓
Circuit OPENS: "Redis is down, stop trying"
                                        ↓
All subsequent requests get instant fallback (empty segments)
                                        ↓
Bid responses in 2ms (no Redis call at all)
                                        ↓
Exchange keeps sending traffic → revenue continues (reduced precision, not zero)
```

## Circuit Breaker State Machine

```
    ┌───────────────┐
    │    CLOSED      │ ← normal operation
    │ (all calls go  │
    │  through)      │
    └───────┬────────┘
            │ failure count >= threshold (5)
            ▼
    ┌───────────────┐
    │    OPEN        │ ← stop calling dependency
    │ (instant       │    return fallback immediately
    │  fallback)     │
    └───────┬────────┘
            │ cooldown (10s) elapsed
            ▼
    ┌───────────────┐
    │  HALF_OPEN     │ ← test: allow ONE call through
    │ (one test      │
    │  call)         │
    └───────┬────────┘
            │
    ┌───────┴───────┐
    │ success       │ failure
    ▼               ▼
  CLOSED          OPEN (restart cooldown)
```

## What happens when each dependency fails

### Redis DOWN

| Component | Fallback | Impact |
|-----------|----------|--------|
| UserSegmentRepository | Return empty segments | No targeting match → 204 no-bid. Bidder serves, just with no personalization. |
| FrequencyCapper.isAllowed() | Return true (allow) | Over-delivery possible until Redis recovers. Conservative: better to show an ad than block. |
| FrequencyCapper.recordImpression() | Silently dropped | Frequency counts under-reported for outage window. Over-delivery possible until Redis recovers. |

### Kafka DOWN

| Component | Fallback | Impact |
|-----------|----------|--------|
| BidEvent publish | Silently dropped | Analytics gap. No billing impact. |
| WinEvent publish | **Logged at WARN** | Billing-critical. Manual reconciliation needed for outage window. |
| ImpressionEvent/ClickEvent | Silently dropped | Attribution gap. |

## Implementation Details

### Thread-safe state transitions

```java
// Only one thread wins the CAS to transition OPEN → HALF_OPEN
if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
    // This thread makes the test call
    // Other threads still get fallback
}
```

AtomicReference for state + AtomicInteger for failure count = lock-free, no synchronization.

### ResilientRedis implements BOTH interfaces

```java
public final class ResilientRedis implements UserSegmentRepository, FrequencyCapper {
```

One circuit breaker protects both segment lookup AND frequency capping — they target the same Redis dependency. If Redis is down for segments, it's down for frequency too. One circuit, one state. (They use separate Lettuce connections internally, but the dependency is the same Redis instance.)

### ResilientEventPublisher wraps any EventPublisher

```java
CircuitBreaker kafkaCB = new CircuitBreaker("kafka-events", 5, 30000);
EventPublisher raw = new KafkaEventPublisher(config, kafkaCB::recordExternalFailure);
EventPublisher resilient = new ResilientEventPublisher(raw, kafkaCB);
```

The circuit breaker is created first so Kafka's async callback can report failures to it. `ResilientEventPublisher` wraps the interface, not the implementation.

### Why sliding window, not consecutive failure count

Consecutive: 4 failures → 1 success → resets to 0 → if Redis is flapping (50% success rate), the circuit never trips. Sliding window: 4 failures + 1 success in 60s = 4 failures still counted → one more trips it. Failures only reset when the window expires.

### Why separate configs for Redis and Kafka

Redis recovers in seconds (container restart). Kafka takes longer (broker rebalance, topic reassignment). Different cooldowns:

```properties
resilience.redis.failure.threshold=5       # trip after 5 failures in 60s
resilience.redis.cooldown.ms=10000         # retry after 10 seconds
resilience.kafka.failure.threshold=5       # trip after 5 failures in 60s
resilience.kafka.cooldown.ms=30000         # retry after 30 seconds (Kafka is slower to recover)
```

### Why Kafka circuit breaker uses external failure reporting

`KafkaEventPublisher.publish()` is async — `producer.send()` returns immediately, the callback fires later on a background thread. The circuit breaker wrapper never sees the exception. Fix: Kafka callback calls `circuitBreaker.recordExternalFailure(exception)` — the circuit breaker detects failures even from async operations.

### Circuit breaker metrics in Prometheus

```
circuit_breaker_state{name="redis"}          0 = CLOSED, 0.5 = HALF_OPEN, 1 = OPEN
circuit_breaker_failures{name="redis"}       current failure count in window
circuit_breaker_trips_total{name="kafka-events"}  total times circuit has tripped
```

Grafana alerts on `circuit_breaker_state == 1` = dependency is down.

## Test Results

### Redis DOWN scenario

```
1. Normal bid (Redis UP):        200 — bid with targeting
2. Stop Redis container
3. 6 bids (Redis DOWN):          All 204 — no crash, graceful degradation
4. Health check:                  {"status":"DOWN","redis":{"status":"DOWN","detail":"timed out"}}
5. Circuit breaker log:           CLOSED → OPEN after 5 failures
6. Restart Redis
7. After cooldown:                OPEN → HALF_OPEN → CLOSED (recovered)
```

**Zero 500 errors during Redis outage.** The bidder kept responding with 204 (no-bid) — the exchange gets a valid response and routes to another bidder. Revenue is reduced (no personalization) but not zero (no crash).

## Files

| File | Purpose |
|------|---------|
| `resilience/CircuitBreaker.java` | State machine: CLOSED → OPEN → HALF_OPEN |
| `resilience/ResilientRedis.java` | Wraps UserSegmentRepository + FrequencyCapper |
| `resilience/ResilientEventPublisher.java` | Wraps EventPublisher (Kafka) |

## How to test

```bash
# Start everything
docker compose up -d redis kafka
mvnw.cmd package
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Normal bid
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"s\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30}]}"

# Stop Redis — verify graceful degradation
docker stop java-rtb-bidder-redis-1
curl -s -X POST http://localhost:8080/bid ...   # Should return 204, not 500
curl http://localhost:8080/health                # Redis shows DOWN

# Restart Redis — circuit recovers after cooldown
docker start java-rtb-bidder-redis-1
# Wait 10s (cooldown), then bid again — should return 200
```
