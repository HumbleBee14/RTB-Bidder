package com.rtb.scoring;

import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-stage cascade scoring — fast scorer filters, accurate scorer refines.
 *
 * Stage 1 (cheap): computes a coarse score for each candidate independently.
 * Stage 2 (expensive): re-scores only candidates whose stage1 score meets
 * or exceeds a configured threshold. Pruned candidates get score -1 (excluded).
 *
 * This is threshold-based pruning, not top-N selection.
 * Example: 100 candidates → stage1 (0.01ms each) → 20 pass threshold → stage2 (1ms each)
 * Total: 100×0.01 + 20×1 = 21ms instead of 100×1 = 100ms
 */
public final class CascadeScorer implements Scorer {

    private static final Logger logger = LoggerFactory.getLogger(CascadeScorer.class);

    private final Scorer stage1;
    private final Scorer stage2;
    private final double stage1Threshold;

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

        // Prune: below threshold → return -1 so RankingStage excludes this candidate
        if (coarseScore < stage1Threshold) {
            return -1.0;
        }

        return stage2.score(campaign, user, context);
    }

    @Override
    public String name() {
        return "Cascade(" + stage1.name() + "→" + stage2.name() + ")";
    }
}
