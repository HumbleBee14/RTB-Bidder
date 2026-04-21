package com.rtb.pacing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Decorator that throttles budget spending based on ML quality score (pCTR × value).
 *
 * Why this matters:
 * A constant-rate pacer spends budget evenly regardless of request quality. But not all
 * requests are equal — some users are 10x more likely to click than others. Spending
 * $1 on a 0.02 pCTR user is wasteful if we could save it for a 0.20 pCTR user later.
 *
 * Quality-based throttling:
 * - score >= highThreshold  → always spend (prime users, full bid)
 * - score <  lowThreshold   → never spend (low-quality, save budget)
 * - score in between        → probabilistic spend, linearly interpolated
 *
 * Example with lowThreshold=0.05, highThreshold=0.20:
 *   score=0.01 → 0% chance to spend (skip)
 *   score=0.05 → 0% chance
 *   score=0.125 → 50% chance
 *   score=0.20 → 100% chance
 *   score=0.50 → 100% chance (capped)
 *
 * Outcome: same daily budget, higher average click-through rate, better ROI.
 *
 * Pure decorator — wraps any BudgetPacer. Budget tracking, pacing, and resilience stay
 * in the inner pacer; this class only decides whether to attempt the spend.
 */
public final class QualityThrottledBudgetPacer implements BudgetPacer {

    private static final Logger logger = LoggerFactory.getLogger(QualityThrottledBudgetPacer.class);

    private final BudgetPacer delegate;
    private final double lowThreshold;
    private final double highThreshold;

    // Observability — how often is throttling kicking in?
    private final LongAdder throttledCount = new LongAdder();
    private final LongAdder highQualitySpendCount = new LongAdder();
    private final LongAdder probabilisticSpendCount = new LongAdder();

    public QualityThrottledBudgetPacer(BudgetPacer delegate, double lowThreshold, double highThreshold) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate pacer must not be null");
        }
        if (!Double.isFinite(lowThreshold) || lowThreshold < 0) {
            throw new IllegalArgumentException("lowThreshold must be finite and >= 0, got: " + lowThreshold);
        }
        if (!Double.isFinite(highThreshold) || highThreshold <= lowThreshold) {
            throw new IllegalArgumentException(
                    "highThreshold must be finite and > lowThreshold (low=" + lowThreshold + ", high=" + highThreshold + ")");
        }
        this.delegate = delegate;
        this.lowThreshold = lowThreshold;
        this.highThreshold = highThreshold;
        logger.info("Quality throttling enabled: low={}, high={}", lowThreshold, highThreshold);
    }

    @Override
    public boolean trySpend(String campaignId, double amount) {
        // No score provided — treat as high-quality (don't penalize callers that don't pass a score)
        return delegate.trySpend(campaignId, amount);
    }

    @Override
    public boolean trySpend(String campaignId, double amount, double qualityScore) {
        if (qualityScore >= highThreshold) {
            highQualitySpendCount.increment();
            // Forward the scored overload — composable with future decorators that may use it.
            // Pacers that don't override the scored method fall through to trySpend(id, amount) via the default.
            return delegate.trySpend(campaignId, amount, qualityScore);
        }

        if (qualityScore < lowThreshold) {
            throttledCount.increment();
            return false;
        }

        // Middle band: probabilistic spend, linearly interpolated
        double spendProbability = (qualityScore - lowThreshold) / (highThreshold - lowThreshold);
        if (ThreadLocalRandom.current().nextDouble() < spendProbability) {
            probabilisticSpendCount.increment();
            return delegate.trySpend(campaignId, amount, qualityScore);
        }

        throttledCount.increment();
        return false;
    }

    @Override
    public double remainingBudget(String campaignId) {
        return delegate.remainingBudget(campaignId);
    }

    public long getThrottledCount() {
        return throttledCount.sum();
    }

    public long getHighQualitySpendCount() {
        return highQualitySpendCount.sum();
    }

    public long getProbabilisticSpendCount() {
        return probabilisticSpendCount.sum();
    }
}
