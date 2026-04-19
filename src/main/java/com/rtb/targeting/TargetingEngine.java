package com.rtb.targeting;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;

import java.util.List;

/** Matches campaigns to a user based on targeting rules. Implementations must be thread-safe. */
public interface TargetingEngine {

    List<AdCandidate> match(List<Campaign> campaigns, UserProfile user, AdContext context);
}
