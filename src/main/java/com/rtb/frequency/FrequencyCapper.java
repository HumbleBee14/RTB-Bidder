package com.rtb.frequency;

/**
 * Frequency capping — prevents users from seeing the same campaign too many times.
 * Implementations must be thread-safe.
 */
public interface FrequencyCapper {

    /** Check if the user is within the cap. Read-only — does not modify state. */
    boolean isAllowed(String userId, String campaignId, int maxImpressions);

    /** Record an impression for the winning campaign. Call only for the served ad. */
    void recordImpression(String userId, String campaignId);
}
