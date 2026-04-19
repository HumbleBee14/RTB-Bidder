package com.rtb.config;

/** Pipeline tuning — SLA deadline and stage configuration. */
public record PipelineConfig(long maxLatencyMs) {

    private static final long MIN_LATENCY_MS = 5;

    public PipelineConfig {
        if (maxLatencyMs < MIN_LATENCY_MS) {
            throw new IllegalArgumentException(
                    "pipeline.sla.maxLatencyMs must be >= " + MIN_LATENCY_MS + ", got: " + maxLatencyMs);
        }
    }

    public static PipelineConfig from(AppConfig config) {
        return new PipelineConfig(
                config.getLong("pipeline.sla.maxLatencyMs", 50)
        );
    }
}
