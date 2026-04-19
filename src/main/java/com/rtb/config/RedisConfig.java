package com.rtb.config;

/** Redis connection configuration. */
public record RedisConfig(String uri, long commandTimeoutMs, long connectTimeoutMs) {

    public RedisConfig {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("redis.uri must not be blank");
        }
        if (commandTimeoutMs < 1) {
            throw new IllegalArgumentException("redis.command.timeout.ms must be >= 1, got: " + commandTimeoutMs);
        }
        if (connectTimeoutMs < 100) {
            throw new IllegalArgumentException("redis.connect.timeout.ms must be >= 100, got: " + connectTimeoutMs);
        }
    }

    public static RedisConfig from(AppConfig config) {
        return new RedisConfig(
                config.get("redis.uri", "redis://localhost:6379"),
                config.getLong("redis.command.timeout.ms", 5),
                config.getLong("redis.connect.timeout.ms", 2000)
        );
    }
}
