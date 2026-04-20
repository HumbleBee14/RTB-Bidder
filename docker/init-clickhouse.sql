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

-- Kafka consumer table — reads from bid-events topic
CREATE TABLE IF NOT EXISTS rtb.bid_events_kafka (
    request_id    String,
    user_id       Nullable(String),
    bid           UInt8,
    no_bid_reason Nullable(String),
    latency_ms    UInt64,
    timestamp     DateTime64(3)
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'bid-events',
    kafka_group_name = 'clickhouse-bid-events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1;

-- Materialized view: auto-inserts from Kafka table into MergeTree
CREATE MATERIALIZED VIEW IF NOT EXISTS rtb.bid_events_mv
TO rtb.bid_events AS
SELECT * FROM rtb.bid_events_kafka;

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
    bid_id         String,
    campaign_id    String,
    clearing_price Float64,
    timestamp      DateTime64(3)
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'win-events',
    kafka_group_name = 'clickhouse-win-events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS rtb.win_events_mv
TO rtb.win_events AS
SELECT * FROM rtb.win_events_kafka;

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
    bid_id      String,
    user_id     String,
    campaign_id String,
    slot_id     String,
    timestamp   DateTime64(3)
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'impression-events',
    kafka_group_name = 'clickhouse-impression-events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS rtb.impression_events_mv
TO rtb.impression_events AS
SELECT * FROM rtb.impression_events_kafka;

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
    bid_id      String,
    user_id     String,
    campaign_id String,
    slot_id     String,
    timestamp   DateTime64(3)
) ENGINE = Kafka()
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'click-events',
    kafka_group_name = 'clickhouse-click-events',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS rtb.click_events_mv
TO rtb.click_events AS
SELECT * FROM rtb.click_events_kafka;
