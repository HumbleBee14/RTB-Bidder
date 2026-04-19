package com.rtb.scoring;

import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;

/** Scores a campaign for a given user and context. Implementations must be thread-safe. */
public interface Scorer {

    double score(Campaign campaign, UserProfile user, AdContext context);

    /** Identifies which scorer produced the result — for logging and A/B comparison. */
    default String name() {
        return getClass().getSimpleName();
    }
}
