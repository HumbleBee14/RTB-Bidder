package com.rtb.health;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import com.rtb.config.RedisConfig;

import java.time.Duration;

/** Pings Redis to check connectivity. */
public final class RedisHealthCheck implements HealthCheck {

    private final RedisConfig config;

    public RedisHealthCheck(RedisConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public HealthStatus check() {
        try {
            RedisURI uri = RedisURI.create(config.uri());
            uri.setTimeout(Duration.ofMillis(config.connectTimeoutMs()));
            RedisClient client = RedisClient.create(uri);
            try (StatefulRedisConnection<String, String> conn = client.connect()) {
                String pong = conn.sync().ping();
                return HealthStatus.up(pong);
            } finally {
                client.shutdown();
            }
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage());
        }
    }
}
