package com.rtb.config;

/** Redis connection configuration. */
public record RedisConfig(String uri, long timeoutMs) {

    private static final long MIN_TIMEOUT_MS = 1;

    public RedisConfig {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("redis.uri must not be blank");
        }
        if (timeoutMs < MIN_TIMEOUT_MS) {
            throw new IllegalArgumentException("redis.timeout.ms must be >= " + MIN_TIMEOUT_MS + ", got: " + timeoutMs);
        }
    }

    public static RedisConfig from(AppConfig config) {
        return new RedisConfig(
                config.get("redis.uri", "redis://localhost:6379"),
                config.getLong("redis.timeout.ms", 5)
        );
    }
}
