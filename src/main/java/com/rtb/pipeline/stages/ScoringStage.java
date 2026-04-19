package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.model.BidRequest;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;
import com.rtb.scoring.Scorer;

import java.util.ArrayList;
import java.util.List;

/** Scores each candidate and filters out those below the exchange bid floor. */
public final class ScoringStage implements PipelineStage {

    private final Scorer scorer;

    public ScoringStage(Scorer scorer) {
        this.scorer = scorer;
    }

    @Override
    public void process(BidContext ctx) {
        BidRequest.AdSlot bestSlot = selectBestSlot(ctx.getRequest().adSlots());
        ctx.setSelectedSlot(bestSlot);

        double exchangeFloor = bestSlot.bidFloor();
        AdContext adContext = AdContext.from(ctx.getRequest());
        List<AdCandidate> candidates = ctx.getCandidates();
        List<AdCandidate> eligible = new ArrayList<>(candidates.size());

        for (AdCandidate candidate : candidates) {
            double score = scorer.score(candidate.getCampaign(), ctx.getUserProfile(), adContext);
            candidate.setScore(score);

            if (candidate.getCampaign().bidFloor() >= exchangeFloor) {
                eligible.add(candidate);
            }
        }

        if (eligible.isEmpty()) {
            ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
            return;
        }

        ctx.setCandidates(eligible);
    }

    /** Pick the slot with the lowest bid floor — best chance of winning. */
    private BidRequest.AdSlot selectBestSlot(List<BidRequest.AdSlot> slots) {
        BidRequest.AdSlot best = slots.get(0);
        for (int i = 1; i < slots.size(); i++) {
            if (slots.get(i).bidFloor() < best.bidFloor()) {
                best = slots.get(i);
            }
        }
        return best;
    }

    @Override
    public String name() {
        return "Scoring";
    }
}
