package com.rtb.scoring;

import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;

/** Scores a campaign for a given user and context. Implementations must be thread-safe. */
public interface Scorer {

    double score(Campaign campaign, UserProfile user, AdContext context);
}
