package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.BidRequest;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the highest-scoring winner for each ad slot.
 * Per-slot: filters by creative size + bid floor, deduplicates across slots.
 */
public final class RankingStage implements PipelineStage {

    @Override
    public void process(BidContext ctx) {
        List<AdCandidate> candidates = ctx.getCandidates();
        List<BidRequest.AdSlot> slots = ctx.getRequest().adSlots();
        Set<String> usedCampaigns = new HashSet<>();

        for (BidRequest.AdSlot slot : slots) {
            AdCandidate winner = pickWinnerForSlot(candidates, slot, usedCampaigns);
            if (winner != null) {
                ctx.setSlotWinner(slot, winner);
                usedCampaigns.add(winner.getCampaign().id());
            }
        }

        if (ctx.getSlotWinners().isEmpty()) {
            ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
        }
    }

    private AdCandidate pickWinnerForSlot(List<AdCandidate> candidates, BidRequest.AdSlot slot,
                                          Set<String> usedCampaigns) {
        AdCandidate best = null;
        for (AdCandidate candidate : candidates) {
            // Skip campaigns already won another slot (dedup)
            if (usedCampaigns.contains(candidate.getCampaign().id())) {
                continue;
            }
            // Creative must fit the slot's sizes
            if (!candidate.getCampaign().fitsSlot(slot.sizes())) {
                continue;
            }
            // Campaign must afford this slot's bid floor
            if (candidate.getCampaign().bidFloor() < slot.bidFloor()) {
                continue;
            }
            // Pick highest score
            if (best == null || candidate.getScore() > best.getScore()) {
                best = candidate;
            }
        }
        return best;
    }

    @Override
    public String name() {
        return "Ranking";
    }
}
