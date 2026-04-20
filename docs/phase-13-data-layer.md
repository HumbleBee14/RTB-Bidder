# Phase 13: PostgreSQL + ClickHouse — Full Data Layer

## What was built

PostgreSQL for campaign storage (replacing the JSON file), ClickHouse for analytics events (consuming from Kafka). CachedCampaignRepository refactored into a true decorator pattern. Config-driven data source selection (JSON or PostgreSQL).

## Why two databases — and why these two specifically

### PostgreSQL: Campaign storage (OLTP)

**Purpose**: Source of truth for campaign data — what advertisers want to show, who to target, how much to spend.

**Why PostgreSQL and not:**
- **Redis**: Campaigns are relational data (columns, constraints, indexes). Redis is a key-value store — no schema enforcement, no SQL queries, no ACID transactions. You'd have to serialize campaigns as JSON blobs and deserialize on every read. Redis is perfect for segments (Sets) and counters (INCR), not for structured relational data.
- **MongoDB**: Could work. But PostgreSQL has native array types (`TEXT[]`) which map directly to our `Set<String>` targeting segments and creative sizes — no embedded document overhead. PostgreSQL also has `pg_trig_log` for change data capture if we ever need campaign change streaming.
- **MySQL**: Would work equally well. PostgreSQL chosen for: native array types, better JSON support, built-in partitioning, and broader adoption in ad-tech (AppNexus, Criteo, AdRoll all use PostgreSQL).
- **DynamoDB/Cassandra**: Over-engineered. We have 10-10K campaigns, not billions. A single PostgreSQL instance handles this trivially. NoSQL's horizontal scaling adds complexity for zero benefit at this data volume.

**Performance characteristics**:
- Campaign data is NOT on the hot path. Loaded at startup, cached in memory.
- The `CachedCampaignRepository` decorator means `getActiveCampaigns()` reads from an `AtomicReference<List<Campaign>>` — zero I/O, zero latency.
- PostgreSQL is only touched on startup (or on periodic refresh). Even if it takes 50ms to load campaigns, that happens once, not per request.
- JDBC (not Vert.x reactive client) is the right choice — blocking I/O on the main thread at startup is fine. Using a reactive client for a one-shot bulk load adds complexity with no benefit.

### ClickHouse: Event analytics (OLAP)

**Purpose**: Store bid, win, impression, click events for analytics queries. Answer questions like "what's my win rate?", "which campaigns are most profitable?", "what's my p99 latency trend?".

**Why ClickHouse and not:**
- **PostgreSQL (same DB)**: Could store events in PostgreSQL. But at 50K events/sec, PostgreSQL's row-oriented storage becomes a bottleneck for analytical queries. A `SELECT campaign_id, count(*), avg(latency_ms) FROM bid_events WHERE timestamp > now() - interval '1 hour' GROUP BY campaign_id` scans every row. ClickHouse stores data column-by-column and compresses each column independently — the same query only reads the 3 columns it needs, with 10-100x compression. This is why OLAP databases exist.
- **Elasticsearch**: Good for full-text search and log analysis, not for aggregate analytics. ClickHouse is 10-100x faster for GROUP BY queries. Elasticsearch also uses more memory per document (inverted index overhead).
- **Apache Druid**: Comparable OLAP performance. But ClickHouse has simpler operations (single binary, SQL interface), native Kafka integration (Kafka engine table — zero-code event ingestion), and better compression. Druid requires separate historical/broker/coordinator nodes.
- **BigQuery/Redshift**: Cloud-hosted OLAP. Great for batch analytics but adds network latency and cloud lock-in. ClickHouse runs locally in Docker — same analytical capability, zero external dependency.
- **Raw Kafka + Kafka Streams**: Could compute aggregates in-stream. But you lose the ability to query historical data ad-hoc. ClickHouse gives you both: real-time ingestion AND SQL queries over all historical data.

**Performance characteristics**:
- MergeTree engine: data is sorted by `(timestamp, request_id)`, partitioned by day, compressed with LZ4. 90-day TTL auto-deletes old data.
- Kafka engine tables: ClickHouse itself acts as a Kafka consumer. No Java consumer code needed. The materialized view auto-inserts each consumed message into the MergeTree table.
- Query speed: sub-second aggregation over millions of rows. ClickHouse processes ~100M rows/sec per core for simple aggregations.

## Architecture: How data flows

```
                    ┌──────────────────────────────────────────────┐
                    │               RTB Bidder                      │
                    │                                              │
  Bid Request ─────>│  Pipeline ──> Response                       │
                    │    │                                         │
                    │    └──> Kafka Producer (async, post-response)│
                    │         │                                    │
                    │    Campaign Cache ◄── CachedCampaignRepository│
                    │         │              (decorator)            │
                    │         ▼                                    │
                    │    PostgresCampaignRepository (startup only)  │
                    └──────────┬───────────────────────────────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │       Kafka          │
                    │  bid-events          │
                    │  win-events          │──────────────────────┐
                    │  impression-events   │                      │
                    │  click-events        │                      ▼
                    └──────────────────────┘         ┌──────────────────────┐
                                                     │     ClickHouse       │
                    ┌──────────────────────┐         │                      │
                    │     PostgreSQL        │         │  Kafka engine tables │
                    │                      │         │       │              │
                    │  campaigns table     │         │       ▼              │
                    │  (source of truth)   │         │  Materialized Views  │
                    │                      │         │       │              │
                    └──────────────────────┘         │       ▼              │
                                                     │  MergeTree tables   │
                                                     │  (analytics queries)│
                                                     └──────────────────────┘
```

## CachedCampaignRepository — Decorator Pattern

The old `CachedCampaignRepository` loaded from a JSON file directly. Now it's a true decorator:

```java
// Before (monolith — loads JSON AND caches):
CampaignRepository repo = new CachedCampaignRepository(objectMapper, "campaigns.json");

// After (decorator — wraps any source):
CampaignRepository source = new JsonCampaignRepository(objectMapper, "campaigns.json");
// or: CampaignRepository source = new PostgresCampaignRepository(pgConfig);
CampaignRepository repo = new CachedCampaignRepository(source);  // decorates either
```

The decorator uses `AtomicReference<List<Campaign>>` for thread-safe cache updates. Readers never see a partial update — they get the old or new list, never a mix.

## ClickHouse Kafka Engine — Zero-Code Event Ingestion

ClickHouse has a unique built-in Kafka integration. Instead of writing a Java Kafka consumer that reads events and inserts into ClickHouse, you create three objects:

1. **Kafka engine table** — ClickHouse connects directly to the Kafka topic as a consumer
2. **MergeTree table** — permanent storage with compression and sorting
3. **Materialized view** — auto-copies each row from Kafka table to MergeTree table

```sql
-- 1. Kafka consumer table
CREATE TABLE bid_events_kafka (...) ENGINE = Kafka()
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = 'bid-events',
         kafka_format = 'JSONEachRow';

-- 2. Storage table
CREATE TABLE bid_events (...) ENGINE = MergeTree()
ORDER BY (timestamp, request_id)
PARTITION BY toYYYYMMDD(timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY;

-- 3. Auto-transfer
CREATE MATERIALIZED VIEW bid_events_mv TO bid_events AS
SELECT * FROM bid_events_kafka;
```

Result: events flow Kafka → ClickHouse with zero application code. ClickHouse commits Kafka offsets automatically. If ClickHouse restarts, it resumes from the last committed offset.

## Sample Analytics Queries

Once events flow, these queries run against ClickHouse:

```sql
-- Fill rate (% of requests that resulted in a bid) — the core business metric
SELECT
    toStartOfHour(timestamp) AS hour,
    countIf(bid = 1) AS bids,
    count() AS requests,
    round(bids / requests * 100, 2) AS fill_rate_pct
FROM rtb.bid_events
WHERE timestamp > now() - INTERVAL 24 HOUR
GROUP BY hour
ORDER BY hour;

-- Win rate by campaign
SELECT
    w.campaign_id,
    count() AS wins,
    round(avg(w.clearing_price), 4) AS avg_clearing_price,
    round(sum(w.clearing_price), 2) AS total_spend
FROM rtb.win_events w
WHERE w.timestamp > now() - INTERVAL 24 HOUR
GROUP BY w.campaign_id
ORDER BY wins DESC;

-- CTR (click-through rate) by campaign — clicks / impressions
SELECT
    i.campaign_id,
    count(DISTINCT i.bid_id) AS impressions,
    count(DISTINCT c.bid_id) AS clicks,
    round(clicks / impressions * 100, 4) AS ctr_pct
FROM rtb.impression_events i
LEFT JOIN rtb.click_events c ON i.bid_id = c.bid_id
WHERE i.timestamp > now() - INTERVAL 24 HOUR
GROUP BY i.campaign_id
ORDER BY ctr_pct DESC;

-- Latency percentiles over time
SELECT
    toStartOfMinute(timestamp) AS minute,
    quantile(0.50)(latency_ms) AS p50,
    quantile(0.99)(latency_ms) AS p99,
    quantile(0.999)(latency_ms) AS p999,
    count() AS requests
FROM rtb.bid_events
WHERE timestamp > now() - INTERVAL 1 HOUR
GROUP BY minute
ORDER BY minute;

-- No-bid breakdown — why are we not bidding?
SELECT
    no_bid_reason,
    count() AS count,
    round(count / sum(count()) OVER () * 100, 2) AS pct
FROM rtb.bid_events
WHERE bid = 0 AND timestamp > now() - INTERVAL 1 HOUR
GROUP BY no_bid_reason
ORDER BY count DESC;
```

## Files

| File | Purpose |
|------|---------|
| `repository/PostgresCampaignRepository.java` | JDBC campaign loader from PostgreSQL |
| `repository/JsonCampaignRepository.java` | Campaign loader from classpath JSON file |
| `repository/CachedCampaignRepository.java` | Decorator — caches any CampaignRepository in memory |
| `config/PostgresConfig.java` | PostgreSQL connection configuration |
| `docker/init-postgres.sql` | Campaign schema + 10 seed campaigns |
| `docker/init-clickhouse.sql` | Kafka engine tables + MergeTree tables + materialized views |
| `docker-compose.yml` | Added PostgreSQL (always-on), ClickHouse with Kafka dependency |
| `Application.java` | Config-driven campaign source: `CAMPAIGNS_SOURCE=json` or `postgres` |

## How to run

### Default mode (JSON — no PostgreSQL needed)

```bash
# Campaigns loaded from src/main/resources/campaigns.json
java -jar target/rtb-bidder-1.0.0.jar
```

### PostgreSQL mode

```bash
# Start infrastructure
docker compose up -d

# Verify campaigns seeded
docker exec <postgres-container> psql -U rtb -c "SELECT id, advertiser FROM campaigns"

# Start bidder with PostgreSQL
CAMPAIGNS_SOURCE=postgres java -jar target/rtb-bidder-1.0.0.jar
```

### ClickHouse analytics

```bash
# After running the bidder with EVENTS_TYPE=kafka, events flow to ClickHouse automatically

# Check if events are flowing
docker exec <clickhouse-container> clickhouse-client -d rtb \
  --query "SELECT count() FROM bid_events"

# Run analytics queries
docker exec <clickhouse-container> clickhouse-client -d rtb \
  --query "SELECT toStartOfHour(timestamp) AS hour, countIf(bid=1) AS bids, count() AS total FROM bid_events GROUP BY hour ORDER BY hour"
```

### ClickHouse init (if tables aren't created automatically)

```bash
# The init SQL should run automatically via docker-entrypoint-initdb.d.
# If tables are missing (check with SHOW TABLES FROM rtb), run manually:
docker exec -i <clickhouse-container> clickhouse-client -d rtb --multiquery \
  < docker/init-clickhouse.sql
```

## Known issue: Windows Docker JDBC auth

On Windows with Docker Desktop, the PostgreSQL JDBC driver fails scram-sha-256 authentication even though `psql` works from within Docker. The password is correct (verified by connecting from another container), but `DriverManager.getConnection()` gets `FATAL: password authentication failed`. This is a Windows Docker Desktop networking/auth specific issue. Works correctly on macOS Docker. Use JSON mode on Windows, PostgreSQL mode on macOS/Linux.
