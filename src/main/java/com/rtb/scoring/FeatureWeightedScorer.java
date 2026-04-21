package com.rtb.scoring;

import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;

import java.util.Set;

/**
 * Scores campaigns using a weighted formula:
 *   score = segmentOverlap × bidFloor × pacingFactor
 *
 * segmentOverlap: ratio of matched segments to total target segments (0.0-1.0)
 * bidFloor: higher-paying campaigns score higher (natural eCPM ranking)
 * pacingFactor: budget remaining as fraction of total (campaigns near exhaustion score lower)
 */
public final class FeatureWeightedScorer implements Scorer {

    @Override
    public double score(Campaign campaign, UserProfile user, AdContext context) {
        double relevance = computeRelevance(campaign.targetSegments(), user.segments());
        double priceFactor = campaign.bidFloor();
        double pacingFactor = computePacingFactor(campaign);

        return relevance * priceFactor * pacingFactor;
    }

    private double computeRelevance(Set<String> targetSegments, Set<String> userSegments) {
        if (targetSegments.isEmpty()) {
            return 0.0;
        }
        long matchCount = 0;
        for (String segment : targetSegments) {
            if (userSegments.contains(segment)) {
                matchCount++;
            }
        }
        return (double) matchCount / targetSegments.size();
    }

    private double computePacingFactor(Campaign campaign) {
        // Constant — pacing is enforced as a separate pipeline stage (BudgetPacingStage),
        // not as a scoring signal. Decorators (hourly, quality-throttling) gate at spend time.
        return 1.0;
    }
}
