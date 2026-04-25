# Phase 1: Skeleton — HTTP Endpoints + OpenRTB-like Protocol

## What was built

Vert.x HTTP server on Netty with the complete RTB endpoint surface, OpenRTB-like request/response models, no-bid handling, and Swagger UI for interactive testing.

## Request Flow

```
  Ad Exchange                          RTB Bidder (Vert.x on Netty)
      │
      │  POST /bid  {user_id, app, ad_slots}
      ├──────────────────────────────────►┐
      │                                   │  BidRequestHandler.handle()
      │                                   │    ├─ parse JSON (ObjectMapper)
      │                                   │    ├─ validate (user_id? ad_slots?)
      │                                   │    ├─ build stub BidResponse
      │                                   │    └─ serialize JSON
      │◄──────────────────────────────────┤
      │  200 {bid_id, price, tracking_urls}
      │  OR 204 (no-bid + X-NoBid-Reason header)
      │
      │  POST /win  {bid_id, clearing_price}     ← we won the auction
      ├──────────────────────────────────►┐
      │◄──────────────────────────────────┤  WinHandler (log, ack)
      │  200 acknowledged
      │
      │                    User's Browser
      │                         │
      │    <img src="/impression?bid_id=xxx">     ← ad rendered
      │                         ├────────►┐
      │                         │◄────────┤  TrackingHandler (1x1 GIF)
      │
      │    click on ad → /click?bid_id=xxx        ← user clicked
      │                         ├────────►┐
      │                         │◄────────┤  TrackingHandler (ack)
```

## Endpoints

| Method | Path | Purpose | Response |
|--------|------|---------|----------|
| `POST` | `/bid` | Core hot path — receive bid request, return bid or no-bid | 200 + BidResponse JSON, or 204 (no-bid) |
| `POST` | `/win` | Win notification — exchange tells us we won the auction | 200 acknowledged |
| `GET` | `/impression?bid_id=xxx` | Impression tracking pixel — ad was rendered on screen | 200 + 1x1 transparent GIF |
| `GET` | `/click?bid_id=xxx` | Click tracking — user clicked the ad | 200 + JSON ack |
| `GET` | `/health` | Health check for load balancer / K8s | 200 `{"status":"UP"}` |
| `GET` | `/metrics` | Prometheus scrape endpoint (stub — Phase 9) | 200 text |
| `GET` | `/docs` | Swagger UI — interactive API testing | HTML |
| `GET` | `/api-docs` | Raw OpenAPI 3.0 spec (YAML) | YAML |

## Models

### BidRequest (OpenRTB-like)
```json
{
  "user_id": "u123",
  "app": { "id": "app1", "category": "news", "bundle": "com.news.app" },
  "device": { "type": "mobile", "os": "android", "geo": "US" },
  "ad_slots": [
    { "id": "slot1", "sizes": ["300x250"], "bid_floor": 0.50 }
  ]
}
```
Java: `BidRequest` record with nested `App`, `Device`, `AdSlot` records. Immutable.

### BidResponse (OpenRTB-like)
```json
{
  "bid_id": "uuid",
  "ad_id": "ad-001",
  "price": 0.60,
  "creative_url": "https://ads.example.com/creative/ad-001.html",
  "tracking_urls": {
    "impression_url": "http://localhost:8080/impression?bid_id=uuid",
    "click_url": "http://localhost:8080/click?bid_id=uuid"
  },
  "advertiser_domain": "example.com"
}
```
Java: `BidResponse` record with nested `TrackingUrls` record. Immutable.

### WinNotification
```json
{
  "bid_id": "bid-abc-123",
  "campaign_id": "camp-001",
  "clearing_price": 0.45
}
```
Java: `WinNotification` record. `clearing_price` may differ from bid price (second-price auction).

### NoBidReason (enum)
`NO_MATCHING_CAMPAIGN` | `ALL_FREQUENCY_CAPPED` | `BUDGET_EXHAUSTED` | `TIMEOUT` | `INTERNAL_ERROR`

Returned in `X-NoBid-Reason` response header on 204.

## Configuration

Config-driven architecture. All values loaded from `application.properties`, overridable via environment variables. See `.env.example` for all available options.

**Resolution order** (highest wins): Environment variable → application.properties → default value.
Env var naming: dots → underscores, uppercase. `server.port` → `SERVER_PORT`.

| Key | Env var | Default | Purpose |
|-----|---------|---------|---------|
| `server.port` | `SERVER_PORT` | `8080` | HTTP listen port |
| `server.body.maxSize` | `SERVER_BODY_MAXSIZE` | `65536` | Max request body (bytes) |
| `pipeline.sla.maxLatencyMs` | `PIPELINE_SLA_MAXLATENCYMS` | `50` | Hard SLA deadline (Phase 2+) |
| `redis.uri` | `REDIS_URI` | `redis://localhost:6379` | Redis connection (Phase 3+) |
| `kafka.bootstrap.servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers (Phase 8+) |

### Logging

Three simultaneous outputs, each toggleable via env var:

| Output | File | Toggle | Format |
|--------|------|--------|--------|
| Console | stdout | `CONSOLE_ENABLED=false` | `12:05:33.123 INFO [thread] class - message` |
| Rolling file | `logs/rtb-bidder.log` | `FILE_ENABLED=false` | ISO 8601 full timestamp |
| JSON | `logs/rtb-bidder.json` | `JSON_ENABLED=false` | Structured JSON for ELK/Loki/Datadog |

Log directory override: `LOG_DIR=/var/log/rtb`. Files roll daily, 100MB max per file, 30 days retention, 1GB total cap.

```bash
# Production example — no console, logs to /var/log
CONSOLE_ENABLED=false LOG_DIR=/var/log/rtb java -jar target/rtb-bidder-1.0.0.jar

# Debug — console only
FILE_ENABLED=false JSON_ENABLED=false java -jar target/rtb-bidder-1.0.0.jar
```

## Files

| File | Purpose |
|------|---------|
| `pom.xml` | Maven build — Vert.x, Jackson, Lettuce, Kafka, Micrometer, Janino |
| `.env.example` | All available config/env vars with defaults |
| `src/main/resources/application.properties` | Default configuration values |
| `src/main/java/com/rtb/Application.java` | Composition root — manual DI, config loading |
| `src/main/java/com/rtb/config/AppConfig.java` | Config loader — properties file + env var override |
| `src/main/java/com/rtb/server/HttpServer.java` | Vert.x server lifecycle |
| `src/main/java/com/rtb/server/BidRouter.java` | All routes configured |
| `src/main/java/com/rtb/server/BidRequestHandler.java` | Hot path — parse, validate, respond or no-bid |
| `src/main/java/com/rtb/server/WinHandler.java` | Win notification handler |
| `src/main/java/com/rtb/server/TrackingHandler.java` | Impression pixel + click tracking |
| `src/main/java/com/rtb/model/BidRequest.java` | Request model (immutable record) |
| `src/main/java/com/rtb/model/BidResponse.java` | Response model (immutable record) |
| `src/main/java/com/rtb/model/WinNotification.java` | Win notification model |
| `src/main/java/com/rtb/model/NoBidReason.java` | No-bid reason enum |
| `src/main/resources/logback.xml` | Logging config (3 outputs, toggleable) |
| `src/main/resources/openapi.yaml` | OpenAPI 3.0 spec |
| `src/main/resources/swagger-ui.html` | Swagger UI page |
| `load-test/sample-bid-request.json` | Sample bid request for curl/k6 |

## How to build and run

```bash
# Dev — compile and run directly (no JAR needed)
mvnw.cmd compile exec:java

# Build fat JAR
mvnw.cmd package

# Production run with JVM tuning
java -XX:+UseZGC -Xms512m -Xmx512m -jar target/rtb-bidder-1.0.0.jar

# Custom port
SERVER_PORT=9090 java -jar target/rtb-bidder-1.0.0.jar
```

## How to test

**Swagger UI** — open http://localhost:8080/docs in browser, click "Try it out" on any endpoint.

**curl commands:**

```bash
# 1. Bid request — returns 200 with bid
curl -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"user_id\":\"u123\",\"app\":{\"id\":\"app1\",\"category\":\"news\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"

# 2. No-bid — missing user_id, returns 204
curl -v -X POST http://localhost:8080/bid -H "Content-Type: application/json" ^
  -d "{\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"slot1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.50}]}"
# Look for: HTTP 204, X-NoBid-Reason: NO_MATCHING_CAMPAIGN

# 3. Win notification — returns 200
curl -X POST http://localhost:8080/win -H "Content-Type: application/json" ^
  -d "{\"bid_id\":\"test-123\",\"campaign_id\":\"camp-001\",\"clearing_price\":0.45}"

# 4. Impression pixel — returns 1x1 transparent GIF
curl http://localhost:8080/impression?bid_id=test-123

# 5. Click tracking — returns JSON ack
curl http://localhost:8080/click?bid_id=test-123

# 6. Health check
curl http://localhost:8080/health

# 7. Metrics (stub)
curl http://localhost:8080/metrics
```

## Design Decisions

### Why 6 endpoints, not just `/bid`
A bidder participates in the full **ad event lifecycle**, not just bidding:

```
Exchange sends bid request  →  POST /bid        →  we respond with bid or 204 (no-bid)
Exchange picks winner       →  POST /win         →  we learn we won (BILLING happens here)
User's device loads the ad  →  GET  /impression   →  ad was actually rendered
User clicks the ad          →  GET  /click        →  click attribution
```

`/health` and `/metrics` are infrastructure — K8s probes and Prometheus scraping.

### No-bid (HTTP 204) is a first-class outcome
In production RTB, **40-60% of requests result in no-bid**. This isn't an error — it means "I have nothing worth bidding on for this user/slot." The `NoBidReason` enum tracks *why*, exposed via `X-NoBid-Reason` header for debugging.

A late response is **worse** than no-bid — the exchange already moved on. This is why Phase 2 adds SLA timeout enforcement.

### Impression tracking returns a 1x1 GIF pixel, not JSON
Standard ad-tech convention. The impression URL is embedded as an `<img>` tag in the rendered ad:
```html
<img src="http://bidder:8080/impression?bid_id=xxx" width="1" height="1"/>
```
When the browser renders the ad, it loads this invisible image → our server logs the impression. This works across all browsers without JavaScript. The response is a 43-byte transparent GIF with `Cache-Control: no-store` (each load = one impression count).

### Manual DI in Application.java (no Spring, no Guice)
The entire object graph is visible in one place — `Application.main()`. Constructor injection only. This isn't just a style choice: Spring's component scanning, AOP proxies, and reflection add measurable per-request overhead that matters when your SLA is 50ms total.

### 64KB body limit on POST routes
RTB bid requests are typically 1-5KB of JSON. The 64KB `BodyHandler` limit is a safety valve — anything larger is either malformed or an attack. Vert.x rejects oversized bodies before parsing, saving CPU.

## Current Limitations (addressed in later phases)
- Bid response is **hardcoded** (always `ad-001` at `bid_floor + $0.10`) — real pipeline in Phase 2
- No Redis, no Kafka, no PostgreSQL — infrastructure comes in Phase 3+
- No SLA timeout enforcement — Phase 2
- Metrics endpoint is a stub — Phase 9
- Health check is always UP — Phase 9 adds dependency checks
