package com.rtb.pacing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decorator that adds hourly pacing and spend smoothing on top of any BudgetPacer.
 *
 * Without hourly pacing, a $1000/day campaign can burn its entire budget in
 * a morning traffic spike. This spreads the budget across remaining hours.
 *
 * Hourly budget = remaining total budget / hours remaining in the day.
 * Recalculated each hour — unspent budget from quiet hours rolls into busy hours.
 *
 * Spend smoothing: as hourly spend approaches the hourly limit, we gradually
 * reduce bid probability instead of a hard cutoff.
 *   < 80% of hourly budget → always bid (100%)
 *   80-95% → bid with decreasing probability (linear ramp down)
 *   > 95% → stop bidding for this campaign this hour
 */
public final class HourlyPacedBudgetPacer implements BudgetPacer {

    private static final Logger logger = LoggerFactory.getLogger(HourlyPacedBudgetPacer.class);

    private static final double SMOOTH_START = 0.80;  // start throttling at 80% hourly spend
    private static final double HARD_STOP = 0.95;     // stop at 95% (leave 5% buffer for race conditions)

    private final BudgetPacer delegate;
    private final int pacingHours;
    private final BudgetMetrics metrics;

    private final ConcurrentHashMap<String, HourlySpend> hourlySpends = new ConcurrentHashMap<>();

    public HourlyPacedBudgetPacer(BudgetPacer delegate, int pacingHours, BudgetMetrics metrics) {
        if (pacingHours < 1 || pacingHours > 24) {
            throw new IllegalArgumentException("pacing.hourly.hours must be 1-24, got: " + pacingHours);
        }
        this.delegate = delegate;
        this.pacingHours = pacingHours;
        this.metrics = metrics;
        logger.info("Hourly pacing enabled: spreading budget across {} hours with spend smoothing", pacingHours);
    }

    @Override
    public boolean trySpend(String campaignId, double amount) {
        double remaining = delegate.remainingBudget(campaignId);
        if (remaining <= 0) return false;

        int currentHour = LocalTime.now().getHour();
        int hoursRemaining = Math.max(1, pacingHours - currentHour);
        double hourlyBudget = remaining / hoursRemaining;

        HourlySpend spend = hourlySpends.computeIfAbsent(campaignId, k -> new HourlySpend());
        spend.resetIfNewHour(currentHour);

        double spentThisHour = spend.getSpentDollars();
        double hourlyUtilization = hourlyBudget > 0 ? spentThisHour / hourlyBudget : 1.0;

        // Spend smoothing: gradually reduce bid probability as hourly budget depletes
        if (hourlyUtilization >= HARD_STOP) {
            metrics.recordThrottled(campaignId);
            logger.debug("Hourly budget exhausted: campaign={}, spent={}, hourlyBudget={}",
                    campaignId, String.format("%.2f", spentThisHour), String.format("%.2f", hourlyBudget));
            return false;
        }

        if (hourlyUtilization >= SMOOTH_START) {
            double bidProbability = 1.0 - (hourlyUtilization - SMOOTH_START) / (HARD_STOP - SMOOTH_START);
            if (ThreadLocalRandom.current().nextDouble() > bidProbability) {
                metrics.recordThrottled(campaignId);
                return false;
            }
        }

        // TODO: ML-driven throttling — adjust bid probability based on predicted conversion value
        // from Scorer, spend more during high-conversion hours. Requires Scorer + Pacer coordination.

        // Passed hourly pacing check → try the actual budget decrement
        boolean success = delegate.trySpend(campaignId, amount);
        if (success) {
            spend.recordSpend(amount);
        }
        return success;
    }

    @Override
    public double remainingBudget(String campaignId) {
        return delegate.remainingBudget(campaignId);
    }

    /** Tracks spend within the current hour for one campaign. Synchronized for hour rollover safety. */
    private static final class HourlySpend {
        private static final long MICRODOLLAR = 1_000_000L;
        private int currentHour = -1;
        private long spentMicros = 0;

        synchronized void resetIfNewHour(int hour) {
            if (currentHour != hour) {
                currentHour = hour;
                spentMicros = 0;
            }
        }

        synchronized void recordSpend(double amount) {
            spentMicros += Math.round(amount * MICRODOLLAR);
        }

        synchronized double getSpentDollars() {
            return spentMicros / (double) MICRODOLLAR;
        }
    }
}
