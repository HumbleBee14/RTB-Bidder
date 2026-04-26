package com.rtb.health;

import io.lettuce.core.api.StatefulRedisConnection;

/** Pings Redis using one of the shared connections — no dedicated health-check connection. */
public final class RedisHealthCheck implements HealthCheck {

    private final StatefulRedisConnection<String, String> connection;

    public RedisHealthCheck(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public HealthStatus check() {
        try {
            String pong = connection.sync().ping();
            return HealthStatus.up(pong);
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage());
        }
    }
}
