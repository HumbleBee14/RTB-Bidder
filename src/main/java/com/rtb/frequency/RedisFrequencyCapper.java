package com.rtb.frequency;

import com.rtb.config.RedisConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Redis-based frequency capping.
 *
 * Key:    freq:{userId}:{campaignId}
 * Value:  impression count
 * TTL:    1 hour window
 *
 * isAllowed() is read-only (GET). recordImpression() uses a Lua script
 * for atomic INCR+EXPIRE in a single round-trip.
 */
public final class RedisFrequencyCapper implements FrequencyCapper, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisFrequencyCapper.class);
    private static final long WINDOW_SECONDS = 3600;

    // Lua script: atomic INCR + set TTL if not already set
    private static final String INCR_WITH_EXPIRE_SCRIPT =
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public RedisFrequencyCapper(RedisConfig config) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofMillis(config.connectTimeoutMs()));

        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands = connection.sync();
        connection.setTimeout(Duration.ofMillis(config.commandTimeoutMs()));

        logger.info("FrequencyCapper connected to Redis: {}:{}", uri.getHost(), uri.getPort());
    }

    @Override
    public boolean isAllowed(String userId, String campaignId, int maxImpressions) {
        String key = "freq:" + userId + ":" + campaignId;
        String value = commands.get(key);
        long count = value != null ? Long.parseLong(value) : 0;
        return count < maxImpressions;
    }

    @Override
    public void recordImpression(String userId, String campaignId) {
        String key = "freq:" + userId + ":" + campaignId;
        commands.eval(INCR_WITH_EXPIRE_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{key}, String.valueOf(WINDOW_SECONDS));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
