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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publishes events to Kafka on a dedicated background thread.
 *
 * All serialization and Kafka send() calls happen OFF the Vert.x event loop.
 * This guarantees the bid path is never blocked by Kafka backpressure,
 * buffer full conditions, or slow metadata fetches.
 */
public final class KafkaEventPublisher implements EventPublisher, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
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
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);

        this.producer = new KafkaProducer<>(props);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Single-threaded executor — events are ordered, no thread contention on producer
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "event-publisher");
            t.setDaemon(true);
            return t;
        });

        logger.info("KafkaEventPublisher connected: {} | topics: {}, {}, {}, {} | offloaded to background thread",
                props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG),
                bidTopic, winTopic, impressionTopic, clickTopic);
    }

    @Override
    public void publishBid(BidEvent event) {
        executor.submit(() -> publish(bidTopic, event.requestId(), event));
    }

    @Override
    public void publishWin(WinEvent event) {
        executor.submit(() -> publish(winTopic, event.bidId(), event));
    }

    @Override
    public void publishImpression(ImpressionEvent event) {
        executor.submit(() -> publish(impressionTopic, event.bidId(), event));
    }

    @Override
    public void publishClick(ClickEvent event) {
        executor.submit(() -> publish(clickTopic, event.bidId(), event));
    }

    /** Runs on the background executor thread — never on the Vert.x event loop. */
    private void publish(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
            // TODO: Phase 10 — circuit breaker + fallback for WinEvents (billing-critical)
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
        executor.shutdown();
        producer.flush();
        producer.close();
        logger.info("KafkaEventPublisher closed");
    }
}
