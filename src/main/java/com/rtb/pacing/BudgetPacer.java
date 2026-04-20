package com.rtb.pacing;

/**
 * Controls campaign budget spending. Implementations must be thread-safe.
 *
 * trySpend() atomically attempts to deduct the bid amount from the campaign's budget.
 * Returns true if the campaign can afford it, false if budget is exhausted.
 */
public interface BudgetPacer {

    boolean trySpend(String campaignId, double amount);

    double remainingBudget(String campaignId);
}
