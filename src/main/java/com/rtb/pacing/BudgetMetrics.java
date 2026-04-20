package com.rtb.pacing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks budget spend/exhaustion metrics per campaign.
 * Simple counters — replaced by Micrometer gauges in Phase 9.
 */
public final class BudgetMetrics {

    private final ConcurrentHashMap<String, AtomicLong> spendCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> exhaustedCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> throttledCount = new ConcurrentHashMap<>();

    public void recordSpend(String campaignId) {
        spendCount.computeIfAbsent(campaignId, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordExhausted(String campaignId) {
        exhaustedCount.computeIfAbsent(campaignId, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordThrottled(String campaignId) {
        throttledCount.computeIfAbsent(campaignId, k -> new AtomicLong()).incrementAndGet();
    }

    public long getSpendCount(String campaignId) {
        AtomicLong count = spendCount.get(campaignId);
        return count != null ? count.get() : 0;
    }

    public long getExhaustedCount(String campaignId) {
        AtomicLong count = exhaustedCount.get(campaignId);
        return count != null ? count.get() : 0;
    }

    public Map<String, AtomicLong> allExhaustedCounts() {
        return Map.copyOf(exhaustedCount);
    }
}
