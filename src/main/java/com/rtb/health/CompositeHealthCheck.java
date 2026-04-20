package com.rtb.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates multiple health checks. Overall status is DOWN if any dependency is DOWN. */
public final class CompositeHealthCheck {

    private final List<HealthCheck> checks;

    public CompositeHealthCheck(List<HealthCheck> checks) {
        this.checks = List.copyOf(checks);
    }

    public Map<String, Object> check() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean allHealthy = true;

        Map<String, Object> components = new LinkedHashMap<>();
        for (HealthCheck hc : checks) {
            HealthCheck.HealthStatus status = hc.check();
            components.put(hc.name(), Map.of(
                    "status", status.healthy() ? "UP" : "DOWN",
                    "detail", status.detail()
            ));
            if (!status.healthy()) {
                allHealthy = false;
            }
        }

        result.put("status", allHealthy ? "UP" : "DOWN");
        result.put("components", components);
        return result;
    }
}
