package com.rtb.frequency;

/** Checks if a user is allowed to see a campaign (not over-exposed). Implementations must be thread-safe. */
public interface FrequencyCapper {

    boolean isAllowed(String userId, String campaignId, int maxImpressions);
}
