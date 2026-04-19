package com.rtb.frequency;

import com.rtb.config.RedisConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Redis-based frequency capping using INCR + EXPIRE.
 *
 * Key:    freq:{userId}:{campaignId}
 * Value:  impression count (auto-incremented)
 * TTL:    1 hour (sliding window resets after expiry)
 */
public final class RedisFrequencyCapper implements FrequencyCapper, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RedisFrequencyCapper.class);
    private static final long WINDOW_SECONDS = 3600; // 1 hour

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public RedisFrequencyCapper(RedisConfig config) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofMillis(config.timeoutMs()));

        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands = connection.sync();
        connection.setTimeout(Duration.ofMillis(config.timeoutMs()));

        logger.info("FrequencyCapper connected to Redis: {}:{}", uri.getHost(), uri.getPort());
    }

    @Override
    public boolean isAllowed(String userId, String campaignId, int maxImpressions) {
        String key = "freq:" + userId + ":" + campaignId;
        long count = commands.incr(key);

        // Set TTL only on first increment (when count == 1)
        if (count == 1) {
            commands.expire(key, WINDOW_SECONDS);
        }

        return count <= maxImpressions;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
