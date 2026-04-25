package com.rtb.repository;

import com.rtb.config.RedisConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/** Fetches user segments from Redis Sets via SMEMBERS. Thread-safe — uses a single shared connection. */
public final class RedisUserSegmentRepository implements UserSegmentRepository, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisUserSegmentRepository.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final Timer smembersTimer;

    public RedisUserSegmentRepository(RedisConfig config, MeterRegistry registry) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofMillis(config.connectTimeoutMs()));

        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands = connection.sync();
        connection.setTimeout(Duration.ofMillis(config.commandTimeoutMs()));

        this.smembersTimer = Timer.builder("redis_client_command_duration_seconds")
                .tag("command", "smembers")
                .description("Lettuce client-observed Redis command latency")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);

        logger.info("Connected to Redis: {}:{}", uri.getHost(), uri.getPort());
    }

    public StatefulRedisConnection<String, String> getConnection() {
        return connection;
    }

    @Override
    public Set<String> getSegments(String userId) {
        String key = "user:" + userId + ":segments";
        Set<String> segments = smembersTimer.record(() -> commands.smembers(key));
        return segments != null ? segments : Collections.emptySet();
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
