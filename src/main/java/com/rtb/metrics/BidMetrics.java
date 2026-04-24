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

    // Full ad-serving funnel — bid → win → impression → click
    // The revenue-driving metrics any ad-tech business watches weekly.
    private final Counter winsTotal;
    private final Counter impressionsTotal;
    private final Counter clicksTotal;
    private final Counter unknownCampaignWinsTotal;

    // Per-stage timer cache — avoid Timer.builder().register() per call on hot path
    private final ConcurrentHashMap<String, Timer> stageTimers = new ConcurrentHashMap<>();

    // Per-campaign counters — bounded cardinality (10-100 campaigns) so safe for
    // Prometheus. Lets ops see which campaigns are winning / losing / burning
    // budget fastest, without scraping logs.
    private final ConcurrentHashMap<String, Counter> campaignBidsByCampaign = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> campaignWinsByCampaign = new ConcurrentHashMap<>();

    // Funnel counters backing win_rate / ctr gauges
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalBids = new AtomicLong();
    private final AtomicLong totalWins = new AtomicLong();
    private final AtomicLong totalImpressions = new AtomicLong();
    private final AtomicLong totalClicks = new AtomicLong();

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

        this.winsTotal = Counter.builder("wins_total")
                .description("Win notifications received from exchange")
                .register(registry);
        this.impressionsTotal = Counter.builder("impressions_total")
                .description("Impression pixels fired")
                .register(registry);
        this.clicksTotal = Counter.builder("clicks_total")
                .description("Click-throughs recorded")
                .register(registry);
        this.unknownCampaignWinsTotal = Counter.builder("win_unknown_campaign_total")
                .description("Win notifications for campaign IDs not in the repository — potential cardinality/DoS probe")
                .register(registry);

        // Fill rate as a gauge — THE core business metric
        registry.gauge("bid_fill_rate", this, m -> {
            long total = m.totalRequests.get();
            return total == 0 ? 0.0 : (double) m.totalBids.get() / total;
        });

        // Win rate = wins / bids. The "did we actually win the auction?" number.
        registry.gauge("win_rate", this, m -> {
            long bids = m.totalBids.get();
            return bids == 0 ? 0.0 : (double) m.totalWins.get() / bids;
        });

        // CTR = clicks / impressions. The revenue-per-show quality signal.
        registry.gauge("ctr", this, m -> {
            long imps = m.totalImpressions.get();
            return imps == 0 ? 0.0 : (double) m.totalClicks.get() / imps;
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

    public void recordWin() {
        winsTotal.increment();
        totalWins.incrementAndGet();
    }

    /**
     * Counts /win notifications with unknown campaign IDs.
     * No campaign_id label — cardinality stays 1 regardless of attack volume.
     * Lets ops see "this is happening and how often" without log amplification.
     */
    public void recordWinUnknownCampaign() {
        unknownCampaignWinsTotal.increment();
    }

    public void recordCampaignBid(String campaignId) {
        campaignBidsByCampaign.computeIfAbsent(campaignId, id ->
                Counter.builder("campaign_bids_total")
                        .tag("campaign_id", id)
                        .description("Bids attributed to a specific campaign")
                        .register(registry)
        ).increment();
    }

    public void recordCampaignWin(String campaignId) {
        campaignWinsByCampaign.computeIfAbsent(campaignId, id ->
                Counter.builder("campaign_wins_total")
                        .tag("campaign_id", id)
                        .description("Wins attributed to a specific campaign")
                        .register(registry)
        ).increment();
    }

    public void recordImpression() {
        impressionsTotal.increment();
        totalImpressions.incrementAndGet();
    }

    public void recordClick() {
        clicksTotal.increment();
        totalClicks.incrementAndGet();
    }
}
