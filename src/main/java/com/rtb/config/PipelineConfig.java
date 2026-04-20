package com.rtb.config;

/** Pipeline tuning — SLA deadline, context pool size. */
public record PipelineConfig(long maxLatencyMs, int contextPoolSize) {

    private static final long MIN_LATENCY_MS = 5;

    public PipelineConfig {
        if (maxLatencyMs < MIN_LATENCY_MS) {
            throw new IllegalArgumentException(
                    "pipeline.sla.maxLatencyMs must be >= " + MIN_LATENCY_MS + ", got: " + maxLatencyMs);
        }
        if (contextPoolSize < 1) {
            throw new IllegalArgumentException(
                    "pipeline.context.pool.size must be >= 1, got: " + contextPoolSize);
        }
    }

    public static PipelineConfig from(AppConfig config) {
        return new PipelineConfig(
                config.getLong("pipeline.sla.maxLatencyMs", 50),
                config.getInt("pipeline.context.pool.size", 256)
        );
    }
}
