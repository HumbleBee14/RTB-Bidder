package com.rtb.health;

/** Checks if a dependency is healthy. Implementations must be thread-safe. */
public interface HealthCheck {

    String name();

    HealthStatus check();

    record HealthStatus(boolean healthy, String detail) {
        public static HealthStatus up() { return new HealthStatus(true, "UP"); }
        public static HealthStatus up(String detail) { return new HealthStatus(true, detail); }
        public static HealthStatus down(String detail) { return new HealthStatus(false, detail); }
    }
}
