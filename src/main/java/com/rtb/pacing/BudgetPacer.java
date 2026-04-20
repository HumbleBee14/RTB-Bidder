package com.rtb.pacing;

/**
 * Controls campaign budget spending. Implementations must be thread-safe.
 *
 * trySpend() atomically attempts to deduct the bid amount from the campaign's budget.
 * Returns true if the campaign can afford it, false if budget is exhausted.
 */
public interface BudgetPacer {

    boolean trySpend(String campaignId, double amount);

    /**
     * Overload that includes the quality score (pCTR × value) of the winning candidate.
     * Default delegates to the unscored version — pacers that want quality-based
     * throttling (e.g., QualityThrottledBudgetPacer) override this.
     */
    default boolean trySpend(String campaignId, double amount, double qualityScore) {
        return trySpend(campaignId, amount);
    }

    double remainingBudget(String campaignId);
}
