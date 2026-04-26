package com.rtb.scoring;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;

import java.util.List;

/** Scores a campaign for a given user and context. Implementations must be thread-safe. */
public interface Scorer {

    double score(Campaign campaign, UserProfile user, AdContext context);

    /**
     * Score every candidate in the list and write the result via {@code candidate.setScore}.
     *
     * Default implementation loops over {@link #score}. Implementations that can amortise
     * per-request work (e.g. encode user features once and reuse across candidates) should
     * override this. The hot path calls {@code scoreAll} per request, not {@code score}.
     */
    default void scoreAll(List<AdCandidate> candidates, UserProfile user, AdContext context) {
        for (AdCandidate candidate : candidates) {
            candidate.setScore(score(candidate.getCampaign(), user, context));
        }
    }

    /** Identifies which scorer produced the result — for logging and A/B comparison. */
    default String name() {
        return getClass().getSimpleName();
    }
}
