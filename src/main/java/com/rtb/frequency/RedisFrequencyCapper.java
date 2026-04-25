package com.rtb.frequency;

import com.rtb.config.RedisConfig;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis-based frequency capping using a round-robin connection array.
 *
 * Why a connection array instead of a pool:
 *   A GenericObjectPool requires borrow/return on every command. Under 5K RPS
 *   with 72 worker threads and only 4 connections, threads queue at borrowObject()
 *   — adding 50+ ms of wait before the Redis command even starts.
 *
 *   A round-robin array (counter % N) has zero lock contention: each thread picks
 *   a connection index atomically, issues the command async, and moves on. All N
 *   connections are permanently open, giving N independent nioEventLoop decoder
 *   threads to share the decoding load.
 *
 *   This is the standard pattern Lettuce recommends for high-throughput workloads
 *   when multiple decoder threads are needed without pool overhead.
 *
 * Key:    freq:{userId}:{campaignId}
 * Value:  impression count (string-encoded integer, absent = 0 impressions)
 * TTL:    1 hour sliding window
 */
public final class RedisFrequencyCapper implements FrequencyCapper, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisFrequencyCapper.class);
    private static final long WINDOW_SECONDS = 3600;

    private static final String INCR_WITH_EXPIRE_SCRIPT =
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String>[] readConnections;
    private final StatefulRedisConnection<String, String>[] writeConnections;
    private final AtomicInteger readRoundRobin = new AtomicInteger();
    private final AtomicInteger writeRoundRobin = new AtomicInteger();
    private final long commandTimeoutMs;
    private final Timer getTimer;
    private final Timer mgetTimer;
    private final Timer evalTimer;

    @SuppressWarnings("unchecked")
    public RedisFrequencyCapper(RedisConfig config, MeterRegistry registry) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofMillis(config.connectTimeoutMs()));

        this.client = RedisClient.create(uri);
        this.commandTimeoutMs = config.commandTimeoutMs();

        int n = config.poolSize();
        this.readConnections = new StatefulRedisConnection[n];
        this.writeConnections = new StatefulRedisConnection[n];
        for (int i = 0; i < n; i++) {
            readConnections[i] = client.connect();
            readConnections[i].setTimeout(Duration.ofMillis(commandTimeoutMs));
            writeConnections[i] = client.connect();
            writeConnections[i].setTimeout(Duration.ofMillis(commandTimeoutMs));
        }

        this.getTimer = Timer.builder("redis_client_command_duration_seconds")
                .tag("command", "get")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);
        this.mgetTimer = Timer.builder("redis_client_command_duration_seconds")
                .tag("command", "mget")
                .description("Batch freq-cap read — one round-trip for all candidates")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);
        this.evalTimer = Timer.builder("redis_client_command_duration_seconds")
                .tag("command", "eval")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);

        logger.info("FrequencyCapper connected to Redis: {}:{} ({} read + {} write connections)",
                uri.getHost(), uri.getPort(), n, n);
    }

    private RedisAsyncCommands<String, String> nextReadCommands() {
        int idx = Math.abs(readRoundRobin.getAndIncrement() % readConnections.length);
        return readConnections[idx].async();
    }

    private RedisAsyncCommands<String, String> nextWriteCommands() {
        int idx = Math.abs(writeRoundRobin.getAndIncrement() % writeConnections.length);
        return writeConnections[idx].async();
    }

    @Override
    public boolean isAllowed(String userId, String campaignId, int maxImpressions) {
        String key = "freq:" + userId + ":" + campaignId;
        return getTimer.record(() -> {
            try {
                String value = nextReadCommands().get(key).get(commandTimeoutMs, TimeUnit.MILLISECONDS);
                long count = value != null ? Long.parseLong(value) : 0;
                return count < maxImpressions;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            } catch (ExecutionException | TimeoutException e) {
                logger.warn("GET freq counter failed for {}/{}: {}", userId, campaignId, e.getMessage());
                return true;
            }
        });
    }

    @Override
    public Set<String> allowedCampaignIds(String userId, Map<String, Integer> campaignMaxImpressions) {
        if (campaignMaxImpressions.isEmpty()) {
            return Set.of();
        }

        List<String> campaignIds = new ArrayList<>(campaignMaxImpressions.keySet());
        String[] keys = new String[campaignIds.size()];
        for (int i = 0; i < campaignIds.size(); i++) {
            keys[i] = "freq:" + userId + ":" + campaignIds.get(i);
        }

        List<KeyValue<String, String>> results = mgetTimer.record(() -> {
            try {
                return nextReadCommands().mget(keys).get(commandTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (ExecutionException | TimeoutException e) {
                logger.warn("MGET freq counters failed for user {} ({} keys): {}", userId, keys.length, e.getMessage());
                return List.of();
            }
        });

        if (results.isEmpty()) {
            return new HashSet<>(campaignIds);
        }

        Set<String> allowed = new HashSet<>();
        for (int i = 0; i < campaignIds.size(); i++) {
            String campaignId = campaignIds.get(i);
            KeyValue<String, String> kv = results.get(i);
            long count = kv.hasValue() ? Long.parseLong(kv.getValue()) : 0;
            if (count < campaignMaxImpressions.get(campaignId)) {
                allowed.add(campaignId);
            }
        }
        return allowed;
    }

    @Override
    public void recordImpression(String userId, String campaignId) {
        String key = "freq:" + userId + ":" + campaignId;
        evalTimer.record(() -> nextWriteCommands().eval(INCR_WITH_EXPIRE_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{key}, String.valueOf(WINDOW_SECONDS)));
    }

    @Override
    public void close() {
        for (StatefulRedisConnection<String, String> conn : readConnections) {
            conn.close();
        }
        for (StatefulRedisConnection<String, String> conn : writeConnections) {
            conn.close();
        }
        client.shutdown();
    }
}
