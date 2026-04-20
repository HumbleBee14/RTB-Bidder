package com.rtb.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Singleton Micrometer registry with Prometheus exporter. */
public final class MetricsRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MetricsRegistry.class);

    private final PrometheusMeterRegistry registry;

    public MetricsRegistry() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        logger.info("Prometheus metrics registry initialized");
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** Scrape output for GET /metrics — Prometheus text format. */
    public String scrape() {
        return registry.scrape();
    }
}
