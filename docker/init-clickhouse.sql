-- ClickHouse analytics schema for RTB events.
--
-- Architecture: Kafka topic → Kafka engine table → Materialized View → MergeTree table
--
-- The Kafka engine table acts as a consumer — ClickHouse reads from the Kafka topic
-- directly (no Java consumer needed). A materialized view transforms and inserts each
-- row into the MergeTree table where it's stored permanently for analytics queries.
--
-- Why MergeTree: ClickHouse's native columnar engine. Compressed, sorted by timestamp,
-- supports real-time inserts and sub-second aggregation queries over billions of rows.
--
-- NOTE: kafka_broker_list uses 'kafka:29092' — the Docker Compose internal listener.
-- This only works when ClickHouse runs in the same Docker network as Kafka.
-- For external ClickHouse, change to the actual Kafka broker address.
--
-- Column naming: Kafka engine tables use camelCase to match the Java JSON serialization
-- (KafkaEventPublisher uses ObjectMapper without SNAKE_CASE strategy).
-- Materialized views rename to snake_case for the MergeTree storage tables.

-- ════════════════════════════════════════════════════════════════════
-- Bid Events
-- ════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS rtb.bid_events (
    request_id    String,
    user_id       Nullable(String),
    bid           UInt8,
    no_bid_reason Nullable(String),
    latency_ms    UInt64,
    timestamp     DateTime64(3),
    inserted_at   DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (timestamp, request_id)
PARTITION BY toYYYYMMDD(timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY;

-- Kafka consumer table — column names match Java JSON field names (camelCase)
CREATE TABLE IF NOT EXISTS rtb.bid_events_kafka (
    requestId         String,
    userId            Nullable(String),
    bid               UInt8,
    noBidReason       Nullable(String),
    pipelineLatencyMs UInt64,
    timestamp         String
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:29092',
    kafka_topic_list = 'bid-events',
    kafka_group_name = 'clickhouse-bid-events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1,
    input_format_skip_unknown_fields = 1;

-- Materialized view: renames camelCase → snake_case, parses ISO-8601 timestamp
CREATE MATERIALIZED VIEW IF NOT EXISTS rtb.bid_events_mv
TO rtb.bid_events AS
SELECT
    requestId AS request_id,
    userId AS user_id,
    bid,
    noBidReason AS no_bid_reason,
    pipelineLatencyMs AS latency_ms,
    parseDateTimeBestEffort(timestamp) AS timestamp
FROM rtb.bid_events_kafka;

-- ════════════════════════════════════════════════════════════════════
-- Win Events
-- ════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS rtb.win_events (
    bid_id         String,
    campaign_id    String,
    clearing_price Float64,
    timestamp      DateTime64(3),
    inserted_at    DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (timestamp, bid_id)
PARTITION BY toYYYYMMDD(timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS rtb.win_events_kafka (
    bidId          String,
    campaignId     String,
    clearingPrice  Float64,
    timestamp      String
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:29092',
    kafka_topic_list = 'win-events',
    kafka_group_name = 'clickhouse-win-events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1,
    input_format_skip_unknown_fields = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS rtb.win_events_mv
TO rtb.win_events AS
SELECT
    bidId AS bid_id,
    campaignId AS campaign_id,
    clearingPrice AS clearing_price,
    parseDateTimeBestEffort(timestamp) AS timestamp
FROM rtb.win_events_kafka;

-- ════════════════════════════════════════════════════════════════════
-- Impression Events
-- ════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS rtb.impression_events (
    bid_id      String,
    user_id     String,
    campaign_id String,
    slot_id     String,
    timestamp   DateTime64(3),
    inserted_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (timestamp, campaign_id, bid_id)
PARTITION BY toYYYYMMDD(timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS rtb.impression_events_kafka (
    bidId      String,
    userId     String,
    campaignId String,
    slotId     String,
    timestamp  String
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:29092',
    kafka_topic_list = 'impression-events',
    kafka_group_name = 'clickhouse-impression-events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1,
    input_format_skip_unknown_fields = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS rtb.impression_events_mv
TO rtb.impression_events AS
SELECT
    bidId AS bid_id,
    userId AS user_id,
    campaignId AS campaign_id,
    slotId AS slot_id,
    parseDateTimeBestEffort(timestamp) AS timestamp
FROM rtb.impression_events_kafka;

-- ════════════════════════════════════════════════════════════════════
-- Click Events
-- ════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS rtb.click_events (
    bid_id      String,
    user_id     String,
    campaign_id String,
    slot_id     String,
    timestamp   DateTime64(3),
    inserted_at DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (timestamp, campaign_id, bid_id)
PARTITION BY toYYYYMMDD(timestamp)
TTL toDateTime(timestamp) + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS rtb.click_events_kafka (
    bidId      String,
    userId     String,
    campaignId String,
    slotId     String,
    timestamp  String
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:29092',
    kafka_topic_list = 'click-events',
    kafka_group_name = 'clickhouse-click-events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1,
    input_format_skip_unknown_fields = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS rtb.click_events_mv
TO rtb.click_events AS
SELECT
    bidId AS bid_id,
    userId AS user_id,
    campaignId AS campaign_id,
    slotId AS slot_id,
    parseDateTimeBestEffort(timestamp) AS timestamp
FROM rtb.click_events_kafka;
