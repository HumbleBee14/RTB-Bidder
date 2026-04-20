package com.rtb.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Micrometer registry with Prometheus exporter. Created once in Application. */
public final class MetricsRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MetricsRegistry.class);

    private final PrometheusMeterRegistry registry;

    public MetricsRegistry() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // Bind JVM + system metrics — exposed as jvm_memory_used_bytes, jvm_gc_pause_seconds, etc.
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        logger.info("Prometheus metrics registry initialized (with JVM + system metrics)");
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** Scrape output for GET /metrics — Prometheus text format. */
    public String scrape() {
        return registry.scrape();
    }
}
