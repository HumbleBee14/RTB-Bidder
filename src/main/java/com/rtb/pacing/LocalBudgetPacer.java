package com.rtb.pacing;

import com.rtb.model.Campaign;
import com.rtb.repository.CampaignRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-instance budget pacing using AtomicLong for lock-free atomic decrements.
 *
 * Budgets are stored as microdollars (budget × 1_000_000) to avoid floating-point
 * precision issues with AtomicLong. $5000.00 = 5_000_000_000 microdollars.
 *
 * Thread-safe: ConcurrentHashMap + AtomicLong.decrementAndGet = no locks.
 */
public final class LocalBudgetPacer implements BudgetPacer {

    private static final Logger logger = LoggerFactory.getLogger(LocalBudgetPacer.class);
    private static final long MICRODOLLAR = 1_000_000L;

    private final ConcurrentHashMap<String, AtomicLong> budgets = new ConcurrentHashMap<>();

    /** Initialize budgets from campaign repository at startup. */
    public LocalBudgetPacer(CampaignRepository campaignRepository) {
        for (Campaign campaign : campaignRepository.getActiveCampaigns()) {
            long budgetMicros = (long) (campaign.budget() * MICRODOLLAR);
            budgets.put(campaign.id(), new AtomicLong(budgetMicros));
        }
        logger.info("Initialized budgets for {} campaigns", budgets.size());
    }

    @Override
    public boolean trySpend(String campaignId, double amount) {
        AtomicLong budget = budgets.get(campaignId);
        if (budget == null) {
            return false;
        }

        long amountMicros = (long) (amount * MICRODOLLAR);
        long remaining = budget.addAndGet(-amountMicros);

        if (remaining < 0) {
            // Overspent — roll back
            budget.addAndGet(amountMicros);
            logger.info("Budget exhausted: campaign={}", campaignId);
            return false;
        }

        return true;
    }

    @Override
    public double remainingBudget(String campaignId) {
        AtomicLong budget = budgets.get(campaignId);
        return budget != null ? (double) budget.get() / MICRODOLLAR : 0.0;
    }
}
