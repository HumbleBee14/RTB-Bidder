package com.rtb.scoring;

import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes traffic between two scorers for A/B testing.
 * Uses user_id hash for deterministic assignment (same user always gets the same variant).
 */
public final class ABTestScorer implements Scorer {

    private static final Logger logger = LoggerFactory.getLogger(ABTestScorer.class);

    private final Scorer controlScorer;
    private final Scorer treatmentScorer;
    private final int treatmentPercentage;

    public ABTestScorer(Scorer controlScorer, Scorer treatmentScorer, int treatmentPercentage) {
        if (treatmentPercentage < 0 || treatmentPercentage > 100) {
            throw new IllegalArgumentException("treatmentPercentage must be 0-100, got: " + treatmentPercentage);
        }
        this.controlScorer = controlScorer;
        this.treatmentScorer = treatmentScorer;
        this.treatmentPercentage = treatmentPercentage;
        logger.info("A/B test: {}% treatment, {}% control", treatmentPercentage, 100 - treatmentPercentage);
    }

    @Override
    public double score(Campaign campaign, UserProfile user, AdContext context) {
        if (isInTreatmentGroup(user.userId())) {
            return treatmentScorer.score(campaign, user, context);
        }
        return controlScorer.score(campaign, user, context);
    }

    /** Deterministic assignment based on user_id hash — same user always gets same variant. */
    private boolean isInTreatmentGroup(String userId) {
        int bucket = (userId.hashCode() & 0x7fffffff) % 100;
        return bucket < treatmentPercentage;
    }
}
