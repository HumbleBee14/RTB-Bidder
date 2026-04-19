package com.rtb.config;

/** Pipeline tuning — SLA deadline and stage configuration. */
public record PipelineConfig(long maxLatencyMs) {

    public static PipelineConfig from(AppConfig config) {
        return new PipelineConfig(
                config.getLong("pipeline.sla.maxLatencyMs", 50)
        );
    }
}
