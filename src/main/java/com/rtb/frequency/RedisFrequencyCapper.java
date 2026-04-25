package com.rtb.frequency;

import com.rtb.config.RedisConfig;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
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

/**
 * Redis-based frequency capping.
 *
 * Key:    freq:{userId}:{campaignId}
 * Value:  impression count (string-encoded integer, absent = 0 impressions)
 * TTL:    1 hour sliding window
 *
 * isAllowed()          — single GET, used when only one campaign is being checked
 * allowedCampaignIds() — MGET for all candidates in ONE round-trip (Phase 17 Slice 5)
 * recordImpression()   — Lua script: atomic INCR + EXPIRE in one round-trip
 */
public final class RedisFrequencyCapper implements FrequencyCapper, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisFrequencyCapper.class);
    private static final long WINDOW_SECONDS = 3600;

    // Atomic INCR + set TTL only on first impression (count == 1 means key was just created)
    private static final String INCR_WITH_EXPIRE_SCRIPT =
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final Timer getTimer;
    private final Timer mgetTimer;
    private final Timer evalTimer;

    public RedisFrequencyCapper(RedisConfig config, MeterRegistry registry) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofMillis(config.connectTimeoutMs()));

        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands = connection.sync();
        connection.setTimeout(Duration.ofMillis(config.commandTimeoutMs()));

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

        logger.info("FrequencyCapper connected to Redis: {}:{}", uri.getHost(), uri.getPort());
    }

    @Override
    public boolean isAllowed(String userId, String campaignId, int maxImpressions) {
        String key = "freq:" + userId + ":" + campaignId;
        String value = getTimer.record(() -> commands.get(key));
        long count = value != null ? Long.parseLong(value) : 0;
        return count < maxImpressions;
    }

    /**
     * MGET override — fetches impression counts for ALL candidate campaigns in one Redis call.
     *
     * Old: 278 serial GETs × 0.1ms = 27.8ms per bid
     * New: 1 MGET (278 keys)       =  0.2ms per bid
     *
     * How MGET works:
     *   Instead of asking Redis 278 separate questions, we send one message with all 278
     *   key names. Redis looks them all up in its hash table, packs the values into one
     *   response, and sends it back. One network round-trip regardless of key count.
     *   Non-existent keys return null (= 0 impressions = user has not seen this campaign).
     */
    @Override
    public Set<String> allowedCampaignIds(String userId, Map<String, Integer> campaignMaxImpressions) {
        if (campaignMaxImpressions.isEmpty()) {
            return Set.of();
        }

        // Preserve insertion order so position i in keys matches position i in mget result
        List<String> campaignIds = new ArrayList<>(campaignMaxImpressions.keySet());
        String[] keys = new String[campaignIds.size()];
        for (int i = 0; i < campaignIds.size(); i++) {
            keys[i] = "freq:" + userId + ":" + campaignIds.get(i);
        }

        // ONE round-trip for all keys
        List<KeyValue<String, String>> results = mgetTimer.record(() -> commands.mget(keys));

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
        evalTimer.record(() -> commands.eval(INCR_WITH_EXPIRE_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{key}, String.valueOf(WINDOW_SECONDS)));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
