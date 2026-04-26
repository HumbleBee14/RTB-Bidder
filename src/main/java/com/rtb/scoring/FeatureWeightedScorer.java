package com.rtb.scoring;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;
import com.rtb.targeting.SegmentBitmap;

import java.util.List;

/**
 * Scores campaigns using a weighted formula:
 *   score = segmentOverlap × bidFloor × pacingFactor
 *
 * segmentOverlap: ratio of matched segments to total target segments (0.0-1.0)
 * bidFloor: higher-paying campaigns score higher (natural eCPM ranking)
 * pacingFactor: budget remaining as fraction of total (campaigns near exhaustion score lower)
 *
 * Relevance computation uses bitmap intersection: target × user bitmaps via bitwise AND
 * + popcount. Same numeric result as the old Set<String> intersection, but a couple of
 * CPU instructions instead of N hash lookups.
 *
 * The hot path calls {@link #scoreAll}, which encodes the user's segments to a bitmap
 * once and reuses that bitmap for every candidate. The single-shot {@link #score} entry
 * point exists for backward compatibility (tests, ad-hoc callers) and re-encodes per call.
 */
public final class FeatureWeightedScorer implements Scorer {

    @Override
    public double score(Campaign campaign, UserProfile user, AdContext context) {
        long userBits = SegmentBitmap.encode(user.segments());
        return scoreOne(campaign, userBits);
    }

    @Override
    public void scoreAll(List<AdCandidate> candidates, UserProfile user, AdContext context) {
        long userBits = SegmentBitmap.encode(user.segments());   // ← encoded once per request
        for (AdCandidate candidate : candidates) {
            candidate.setScore(scoreOne(candidate.getCampaign(), userBits));
        }
    }

    private double scoreOne(Campaign campaign, long userBits) {
        double relevance = computeRelevance(campaign, userBits);
        double priceFactor = campaign.bidFloor();
        double pacingFactor = computePacingFactor(campaign);
        return relevance * priceFactor * pacingFactor;
    }

    private double computeRelevance(Campaign campaign, long userBits) {
        int targetSize = campaign.targetSegments().size();
        if (targetSize == 0) {
            return 0.0;
        }
        long targetBits = SegmentBitmap.forCampaign(campaign);
        int matchCount  = Long.bitCount(targetBits & userBits);
        return (double) matchCount / targetSize;
    }

    private double computePacingFactor(Campaign campaign) {
        // Constant — pacing is enforced as a separate pipeline stage (BudgetPacingStage),
        // not as a scoring signal. Decorators (hourly, quality-throttling) gate at spend time.
        return 1.0;
    }
}
