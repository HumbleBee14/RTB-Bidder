package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.BidRequest;
import com.rtb.model.NoBidReason;
import com.rtb.pacing.BudgetPacer;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Attempts to spend budget for each slot's winner.
 * If the winner's budget is exhausted, iterates through ALL fallback candidates
 * until one succeeds or all are exhausted.
 */
public final class BudgetPacingStage implements PipelineStage {

    private final BudgetPacer budgetPacer;

    public BudgetPacingStage(BudgetPacer budgetPacer) {
        this.budgetPacer = budgetPacer;
    }

    @Override
    public void process(BidContext ctx) {
        Map<BidRequest.AdSlot, AdCandidate> currentWinners = ctx.getSlotWinners();
        List<AdCandidate> allCandidates = ctx.getCandidates();
        Set<String> usedCampaigns = new HashSet<>();
        Map<BidRequest.AdSlot, AdCandidate> confirmedWinners = new LinkedHashMap<>();

        for (Map.Entry<BidRequest.AdSlot, AdCandidate> entry : currentWinners.entrySet()) {
            BidRequest.AdSlot slot = entry.getKey();
            AdCandidate winner = entry.getValue();

            AdCandidate confirmed = trySpendOrFallback(winner, allCandidates, slot, usedCampaigns);
            if (confirmed != null) {
                confirmedWinners.put(slot, confirmed);
                usedCampaigns.add(confirmed.getCampaign().id());
            }
        }

        ctx.getSlotWinners().clear();
        for (Map.Entry<BidRequest.AdSlot, AdCandidate> entry : confirmedWinners.entrySet()) {
            ctx.setSlotWinner(entry.getKey(), entry.getValue());
        }

        if (ctx.getSlotWinners().isEmpty()) {
            ctx.abort(NoBidReason.BUDGET_EXHAUSTED);
        }
    }

    /** Try the winner first, then iterate ALL fallback candidates by score until one succeeds. */
    private AdCandidate trySpendOrFallback(AdCandidate winner, List<AdCandidate> allCandidates,
                                           BidRequest.AdSlot slot, Set<String> usedCampaigns) {
        // Try the original winner first. Pass the candidate's score — quality-aware pacers
        // (QualityThrottledBudgetPacer) use this to throttle low-pCTR spends.
        double bidPrice = Math.max(winner.getCampaign().bidFloor(), slot.bidFloor());
        if (!usedCampaigns.contains(winner.getCampaign().id())
                && budgetPacer.trySpend(winner.getCampaign().id(), bidPrice, winner.getScore())) {
            return winner;
        }

        // Iterate all candidates sorted by score, try each until one succeeds
        Set<String> tried = new HashSet<>();
        tried.add(winner.getCampaign().id());

        while (true) {
            AdCandidate fallback = findNextBest(allCandidates, slot, usedCampaigns, tried);
            if (fallback == null) {
                return null;
            }

            double fallbackPrice = Math.max(fallback.getCampaign().bidFloor(), slot.bidFloor());
            if (budgetPacer.trySpend(fallback.getCampaign().id(), fallbackPrice, fallback.getScore())) {
                return fallback;
            }

            tried.add(fallback.getCampaign().id());
        }
    }

    private AdCandidate findNextBest(List<AdCandidate> candidates, BidRequest.AdSlot slot,
                                     Set<String> usedCampaigns, Set<String> tried) {
        AdCandidate best = null;
        for (AdCandidate candidate : candidates) {
            if (candidate.getScore() < 0) continue;
            if (tried.contains(candidate.getCampaign().id())) continue;
            if (usedCampaigns.contains(candidate.getCampaign().id())) continue;
            if (!candidate.getCampaign().fitsSlot(slot.sizes())) continue;
            if (candidate.getCampaign().bidFloor() < slot.bidFloor()) continue;
            if (best == null || candidate.getScore() > best.getScore()) {
                best = candidate;
            }
        }
        return best;
    }

    @Override
    public String name() {
        return "BudgetPacing";
    }
}
