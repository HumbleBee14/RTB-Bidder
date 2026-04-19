package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
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
        double exchangeFloor = ctx.getRequest().adSlots().get(0).bidFloor();
        AdContext adContext = AdContext.from(ctx.getRequest());
        List<AdCandidate> candidates = ctx.getCandidates();
        List<AdCandidate> eligible = new ArrayList<>();

        for (AdCandidate candidate : candidates) {
            double score = scorer.score(candidate.getCampaign(), ctx.getUserProfile(), adContext);
            candidate.setScore(score);

            // Filter: campaign can't afford this slot
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

    @Override
    public String name() {
        return "Scoring";
    }
}
