package com.rtb.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core bidder metrics — the RED method: Rate, Errors, Duration.
 *
 * These are THE metrics that appear on production ad-tech dashboards.
 * Prometheus scrapes /metrics every 15s and Grafana visualizes percentiles.
 */
public final class BidMetrics {

    private final MeterRegistry registry;

    // Rate
    private final Counter bidRequestsTotal;
    private final Counter bidResponsesBid;
    private final Counter bidResponsesError;
    private final Counter bidResponsesTimeout;

    // No-bid counters keyed by NoBidReason — lets ops answer "why did fill rate drop?"
    // Without this, a drop in fill rate looks identical whether caused by frequency
    // capping, budget exhaustion, or a targeting regression.
    private final ConcurrentHashMap<String, Counter> noBidByReason = new ConcurrentHashMap<>();

    // Duration
    private final Timer bidLatency;

    // Business metrics
    private final Counter frequencyCapHitTotal;
    private final Counter budgetExhaustedTotal;

    // Per-stage timer cache — avoid Timer.builder().register() per call on hot path
    private final ConcurrentHashMap<String, Timer> stageTimers = new ConcurrentHashMap<>();

    // Fill rate tracking
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalBids = new AtomicLong();

    public BidMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.bidRequestsTotal = Counter.builder("bid_requests_total")
                .description("Total bid requests received")
                .register(registry);

        this.bidResponsesBid = Counter.builder("bid_responses_total")
                .tag("outcome", "bid")
                .tag("reason", "matched")
                .description("Bid responses by outcome and reason")
                .register(registry);

        this.bidResponsesError = Counter.builder("bid_responses_total")
                .tag("outcome", "error")
                .tag("reason", "INTERNAL_ERROR")
                .register(registry);

        this.bidResponsesTimeout = Counter.builder("bid_responses_total")
                .tag("outcome", "timeout")
                .tag("reason", "TIMEOUT")
                .register(registry);

        this.bidLatency = Timer.builder("bid_latency_seconds")
                .description("End-to-end bid request latency")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);

        this.frequencyCapHitTotal = Counter.builder("frequency_cap_hit_total")
                .description("Requests where all candidates were frequency capped")
                .register(registry);

        this.budgetExhaustedTotal = Counter.builder("budget_exhausted_total")
                .description("Requests where winner budget was exhausted")
                .register(registry);

        // Fill rate as a gauge — THE core business metric
        registry.gauge("bid_fill_rate", this, m -> {
            long total = m.totalRequests.get();
            return total == 0 ? 0.0 : (double) m.totalBids.get() / total;
        });
    }

    public void recordRequest() {
        bidRequestsTotal.increment();
        totalRequests.incrementAndGet();
    }

    public void recordBid(long latencyNanos) {
        bidResponsesBid.increment();
        totalBids.incrementAndGet();
        bidLatency.record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    public void recordNoBid(String reason, long latencyNanos) {
        switch (reason) {
            case "TIMEOUT" -> bidResponsesTimeout.increment();
            case "INTERNAL_ERROR" -> bidResponsesError.increment();
            default -> noBidByReason.computeIfAbsent(reason, r ->
                    Counter.builder("bid_responses_total")
                            .tag("outcome", "nobid")
                            .tag("reason", r)
                            .register(registry)
            ).increment();
        }
        bidLatency.record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    public void recordError(long latencyNanos) {
        bidResponsesError.increment();
        bidLatency.record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    public void recordStageLatency(String stageName, long latencyNanos) {
        stageTimers.computeIfAbsent(stageName, name ->
                Timer.builder("pipeline_stage_latency_seconds")
                        .tag("stage", name)
                        .register(registry)
        ).record(latencyNanos, TimeUnit.NANOSECONDS);
    }

    public void recordFrequencyCapHit() {
        frequencyCapHitTotal.increment();
    }

    public void recordBudgetExhausted() {
        budgetExhaustedTotal.increment();
    }
}
