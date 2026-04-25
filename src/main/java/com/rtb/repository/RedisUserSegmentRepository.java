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

/**
 * Fetches user segments from Redis Sets via SMEMBERS.
 *
 * Uses Lettuce's async API on a single shared connection. The calling worker
 * thread still blocks on the returned future ({@code .get(timeout)}), so the
 * caller's contract is unchanged from the previous synchronous implementation —
 * but multiple worker threads can now have commands in flight on the same
 * connection concurrently, lifting per-connection throughput from ~5K ops/s
 * (sync API ceiling) to ~100K ops/s (async + auto-flush).
 *
 * This was the leading suspect for the 5K RPS saturation knee observed in
 * Run 3. See docs/perf/improvements.md §1 for the rationale and
 * docs/notes/profiling.md for how we'll verify the change with flame graphs.
 */
public final class RedisUserSegmentRepository implements UserSegmentRepository, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisUserSegmentRepository.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> commands;
    private final long commandTimeoutMs;
    private final Timer smembersTimer;

    public RedisUserSegmentRepository(RedisConfig config, MeterRegistry registry) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofMillis(config.connectTimeoutMs()));

        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands = connection.async();
        connection.setTimeout(Duration.ofMillis(config.commandTimeoutMs()));
        this.commandTimeoutMs = config.commandTimeoutMs();

        this.smembersTimer = Timer.builder("redis_client_command_duration_seconds")
                .tag("command", "smembers")
                .description("Lettuce client-observed Redis command latency")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);

        logger.info("Connected to Redis: {}:{} (async API)", uri.getHost(), uri.getPort());
    }

    public StatefulRedisConnection<String, String> getConnection() {
        return connection;
    }

    @Override
    public Set<String> getSegments(String userId) {
        String key = "user:" + userId + ":segments";
        return smembersTimer.record(() -> {
            try {
                Set<String> segments = commands.smembers(key).get(commandTimeoutMs, TimeUnit.MILLISECONDS);
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
        connection.close();
        client.shutdown();
    }
}
