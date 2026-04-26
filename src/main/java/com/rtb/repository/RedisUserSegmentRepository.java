package com.rtb.repository;

import com.rtb.config.RedisConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetches user segments from Redis Sets via SMEMBERS.
 *
 * Uses a round-robin connection array for the same reason as RedisFrequencyCapper:
 * a GenericObjectPool adds borrow/return latency under high concurrency. A fixed
 * array of N connections gives N decoder threads with zero acquisition overhead.
 */
public final class RedisUserSegmentRepository implements UserSegmentRepository, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisUserSegmentRepository.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String>[] connections;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final long commandTimeoutMs;
    private final Timer smembersTimer;

    @SuppressWarnings("unchecked")
    public RedisUserSegmentRepository(RedisConfig config, MeterRegistry registry) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofMillis(config.connectTimeoutMs()));

        this.client = RedisClient.create(uri);
        this.commandTimeoutMs = config.commandTimeoutMs();

        int n = config.poolSize();
        this.connections = new StatefulRedisConnection[n];
        for (int i = 0; i < n; i++) {
            connections[i] = client.connect();
            connections[i].setTimeout(Duration.ofMillis(commandTimeoutMs));
        }

        this.smembersTimer = Timer.builder("redis_client_command_duration_seconds")
                .tag("command", "smembers")
                .description("Lettuce client-observed Redis command latency")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);

        logger.info("Connected to Redis: {}:{} ({} connections)", uri.getHost(), uri.getPort(), n);
    }

    private RedisAsyncCommands<String, String> nextCommands() {
        int idx = Math.abs(roundRobin.getAndIncrement() % connections.length);
        return connections[idx].async();
    }

    public StatefulRedisConnection<String, String> getConnection() {
        return connections[0];
    }

    @Override
    public Set<String> getSegments(String userId) {
        String key = "user:" + userId + ":segments";
        return smembersTimer.record(() -> {
            try {
                Set<String> segments = nextCommands().smembers(key).get(commandTimeoutMs, TimeUnit.MILLISECONDS);
                return segments != null ? segments : Collections.emptySet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Collections.emptySet();
            } catch (ExecutionException | TimeoutException e) {
                logger.warn("SMEMBERS failed for {}: {}", userId, e.getMessage());
                return Collections.emptySet();
            }
        });
    }

    @Override
    public void close() {
        for (StatefulRedisConnection<String, String> conn : connections) {
            conn.close();
        }
        client.shutdown();
    }
}
