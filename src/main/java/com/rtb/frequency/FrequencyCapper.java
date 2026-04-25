package com.rtb.frequency;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Frequency capping — prevents users from seeing the same campaign too many times.
 * Implementations must be thread-safe.
 */
public interface FrequencyCapper {

    /** Check if the user is within the cap for one campaign. Read-only. */
    boolean isAllowed(String userId, String campaignId, int maxImpressions);

    /** Record an impression for the winning campaign. Call only for the served ad. */
    void recordImpression(String userId, String campaignId);

    /**
     * Batch check: which campaign IDs are the user still allowed to see?
     *
     * Default: calls isAllowed() per campaign — one Redis GET each (278 round-trips).
     * Override in Redis implementations with MGET to collapse to one round-trip.
     *
     * @param campaignMaxImpressions  map of campaignId → hourly impression cap
     * @return set of campaign IDs the user has NOT exhausted
     */
    default Set<String> allowedCampaignIds(String userId,
                                            Map<String, Integer> campaignMaxImpressions) {
        Set<String> allowed = new HashSet<>();
        for (Map.Entry<String, Integer> e : campaignMaxImpressions.entrySet()) {
            if (isAllowed(userId, e.getKey(), e.getValue())) {
                allowed.add(e.getKey());
            }
        }
        return allowed;
    }
}
