package com.rtb.model;

import java.util.Set;

/** An ad campaign with targeting rules, budget, and creative. */
public record Campaign(
        String id,
        String advertiser,
        double budget,
        double bidFloor,
        Set<String> targetSegments,
        Set<String> creativeSizes,
        String creativeUrl,
        String advertiserDomain,
        int maxImpressionsPerHour,
        double valuePerClick
) {
    /** Check if this campaign has a creative that fits the given slot sizes. */
    public boolean fitsSlot(java.util.List<String> slotSizes) {
        for (String size : slotSizes) {
            if (creativeSizes.contains(size)) {
                return true;
            }
        }
        return false;
    }
}
