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
        // Single batch call — implementations can amortise per-request work (e.g.
        // FeatureWeightedScorer encodes user segments to a bitmap once).
        scorer.scoreAll(candidates, ctx.getUserProfile(), adContext);
    }

    @Override
    public String name() {
        return "Scoring(" + scorer.name() + ")";
    }
}
