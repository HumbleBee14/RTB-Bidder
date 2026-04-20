package com.rtb.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rtb.config.AppConfig;
import com.rtb.event.events.BidEvent;
import com.rtb.event.events.ClickEvent;
import com.rtb.event.events.ImpressionEvent;
import com.rtb.event.events.WinEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Publishes events to Kafka asynchronously. Fire-and-forget — never blocks the bid path.
 *
 * Uses async send with a callback that logs errors but doesn't retry or throw.
 * Batching and compression are configured for throughput over latency.
 */
public final class KafkaEventPublisher implements EventPublisher, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;
    private final String bidTopic;
    private final String winTopic;
    private final String impressionTopic;
    private final String clickTopic;

    public KafkaEventPublisher(AppConfig config) {
        this.bidTopic = config.get("kafka.topic.bid-events", "bid-events");
        this.winTopic = config.get("kafka.topic.win-events", "win-events");
        this.impressionTopic = config.get("kafka.topic.impression-events", "impression-events");
        this.clickTopic = config.get("kafka.topic.click-events", "click-events");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.get("kafka.bootstrap.servers", "localhost:9092"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Async batching for throughput — not latency-sensitive (events are post-response)
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // Fire-and-forget: acks=1 (leader only, no replica wait)
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        // Fail fast on startup if Kafka is unreachable (default is 60s which blocks main thread)
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);

        this.producer = new KafkaProducer<>(props);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        logger.info("KafkaEventPublisher connected: {} | topics: {}, {}, {}, {}",
                props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG),
                bidTopic, winTopic, impressionTopic, clickTopic);
    }

    @Override
    public void publishBid(BidEvent event) {
        publish(bidTopic, event.requestId(), event);
    }

    @Override
    public void publishWin(WinEvent event) {
        publish(winTopic, event.bidId(), event);
    }

    @Override
    public void publishImpression(ImpressionEvent event) {
        publish(impressionTopic, event.bidId(), event);
    }

    @Override
    public void publishClick(ClickEvent event) {
        publish(clickTopic, event.bidId(), event);
    }

    private void publish(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
            // TODO: Phase 10 — add circuit breaker + fallback (write to local file if Kafka down)
            // For billing-critical WinEvents, consider acks=all + retry for durability
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    logger.error("Failed to publish to topic {} key={}", topic, key, exception);
                }
            });
        } catch (Exception e) {
            logger.error("Failed to serialize event for topic {} key={}", topic, key, e);
        }
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
        logger.info("KafkaEventPublisher closed");
    }
}
