package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;
import com.rtb.scoring.Scorer;

import java.util.List;

/**
 * Scores each candidate ONCE (scores are user-dependent, not slot-dependent).
 * Filtering by bid floor and creative size happens per-slot in RankingStage.
 */
public final class ScoringStage implements PipelineStage {

    private final Scorer scorer;

    public ScoringStage(Scorer scorer) {
        this.scorer = scorer;
    }

    @Override
    public void process(BidContext ctx) {
        AdContext adContext = AdContext.from(ctx.getRequest());
        List<AdCandidate> candidates = ctx.getCandidates();

        for (AdCandidate candidate : candidates) {
            double score = scorer.score(candidate.getCampaign(), ctx.getUserProfile(), adContext);
            candidate.setScore(score);
        }
    }

    @Override
    public String name() {
        return "Scoring";
    }
}
