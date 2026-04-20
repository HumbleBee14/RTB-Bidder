package com.rtb.pacing;

import com.rtb.model.Campaign;
import com.rtb.repository.CampaignRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-instance budget pacing using AtomicLong with CAS loop for lock-free atomic decrements.
 *
 * Budgets stored as microdollars (budget × 1_000_000) to avoid floating-point precision issues.
 * Thread-safe: ConcurrentHashMap + AtomicLong.compareAndSet = lock-free, race-free.
 */
public final class LocalBudgetPacer implements BudgetPacer {

    private static final Logger logger = LoggerFactory.getLogger(LocalBudgetPacer.class);
    private static final long MICRODOLLAR = 1_000_000L;

    private final ConcurrentHashMap<String, AtomicLong> budgets = new ConcurrentHashMap<>();
    private final BudgetMetrics metrics;

    public LocalBudgetPacer(CampaignRepository campaignRepository, BudgetMetrics metrics) {
        this.metrics = metrics;
        for (Campaign campaign : campaignRepository.getActiveCampaigns()) {
            long budgetMicros = Math.round(campaign.budget() * MICRODOLLAR);
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

        long amountMicros = Math.round(amount * MICRODOLLAR);
        if (amountMicros <= 0) return false;

        long current;
        long next;
        do {
            current = budget.get();
            next = current - amountMicros;
            if (next < 0) {
                metrics.recordExhausted(campaignId);
                logger.debug("Budget exhausted: campaign={}", campaignId);
                return false;
            }
        } while (!budget.compareAndSet(current, next));

        metrics.recordSpend(campaignId);
        return true;
    }

    @Override
    public double remainingBudget(String campaignId) {
        AtomicLong budget = budgets.get(campaignId);
        return budget != null ? (double) budget.get() / MICRODOLLAR : 0.0;
    }
}
