package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

import java.util.List;

/** Picks the highest-scoring candidate as the winner (O(n) max-scan). */
public final class RankingStage implements PipelineStage {

    @Override
    public void process(BidContext ctx) {
        List<AdCandidate> candidates = ctx.getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
            return;
        }

        AdCandidate winner = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            if (candidates.get(i).getScore() > winner.getScore()) {
                winner = candidates.get(i);
            }
        }

        ctx.setWinner(winner);
    }

    @Override
    public String name() {
        return "Ranking";
    }
}
