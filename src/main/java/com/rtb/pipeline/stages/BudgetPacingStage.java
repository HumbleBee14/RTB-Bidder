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
 * If the winner's budget is exhausted, falls back to the next eligible candidate.
 * Runs AFTER RankingStage — re-validates the winners against budget constraints.
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

            double bidPrice = Math.max(winner.getCampaign().bidFloor(), slot.bidFloor());

            if (budgetPacer.trySpend(winner.getCampaign().id(), bidPrice)) {
                confirmedWinners.put(slot, winner);
                usedCampaigns.add(winner.getCampaign().id());
            } else {
                // Budget exhausted — try fallback candidates for this slot
                AdCandidate fallback = findFallback(allCandidates, slot, usedCampaigns);
                if (fallback != null) {
                    double fallbackPrice = Math.max(fallback.getCampaign().bidFloor(), slot.bidFloor());
                    if (budgetPacer.trySpend(fallback.getCampaign().id(), fallbackPrice)) {
                        confirmedWinners.put(slot, fallback);
                        usedCampaigns.add(fallback.getCampaign().id());
                    }
                }
            }
        }

        // Replace slot winners with budget-confirmed winners
        ctx.getSlotWinners().clear();
        for (Map.Entry<BidRequest.AdSlot, AdCandidate> entry : confirmedWinners.entrySet()) {
            ctx.setSlotWinner(entry.getKey(), entry.getValue());
        }

        if (ctx.getSlotWinners().isEmpty()) {
            ctx.abort(NoBidReason.BUDGET_EXHAUSTED);
        }
    }

    private AdCandidate findFallback(List<AdCandidate> candidates, BidRequest.AdSlot slot,
                                     Set<String> usedCampaigns) {
        AdCandidate best = null;
        for (AdCandidate candidate : candidates) {
            if (candidate.getScore() < 0) continue;
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
