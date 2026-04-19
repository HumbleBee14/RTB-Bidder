package com.rtb.model;

import java.util.Set;

/** An ad campaign with targeting rules, budget, and creative. */
public record Campaign(
        String id,
        String advertiser,
        double budget,
        double bidFloor,
        Set<String> targetSegments,
        String creativeUrl,
        String advertiserDomain,
        int maxImpressionsPerHour
) {}
