package com.rtb.scoring;

import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-stage cascade scoring — fast scorer filters, accurate scorer refines.
 *
 * Stage 1 (cheap): scores all candidates, returns a coarse ranking.
 * Stage 2 (expensive): re-scores only the top-N candidates from stage 1.
 *
 * This is how production ad-tech works at scale:
 *   - 100 candidates → stage1 (formula, 0.01ms each) → top 20
 *   - 20 candidates → stage2 (ML model, 1ms each) → final ranking
 *   - Total: 100×0.01 + 20×1 = 21ms instead of 100×1 = 100ms
 *
 * The final score comes from stage2 (the accurate scorer).
 * Stage1 is only used for pruning — its score is discarded.
 */
public final class CascadeScorer implements Scorer {

    private static final Logger logger = LoggerFactory.getLogger(CascadeScorer.class);

    private final Scorer stage1;
    private final Scorer stage2;
    private final double stage1Threshold;

    /**
     * @param stage1 fast scorer for initial filtering
     * @param stage2 accurate scorer for final ranking
     * @param stage1Threshold minimum stage1 score to pass to stage2 (0.0 passes all)
     */
    public CascadeScorer(Scorer stage1, Scorer stage2, double stage1Threshold) {
        this.stage1 = stage1;
        this.stage2 = stage2;
        this.stage1Threshold = stage1Threshold;
        logger.info("Cascade scorer: stage1={}, stage2={}, threshold={}",
                stage1.name(), stage2.name(), stage1Threshold);
    }

    @Override
    public double score(Campaign campaign, UserProfile user, AdContext context) {
        double coarseScore = stage1.score(campaign, user, context);

        // Prune: if stage1 score is below threshold, skip expensive stage2
        if (coarseScore < stage1Threshold) {
            return 0.0;
        }

        // Re-score with the accurate model
        return stage2.score(campaign, user, context);
    }

    @Override
    public String name() {
        return "Cascade(" + stage1.name() + "→" + stage2.name() + ")";
    }
}
