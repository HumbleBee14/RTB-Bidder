package com.rtb.event.events;

import java.time.Instant;

/** Published when the ad is actually rendered on the user's screen. */
public record ImpressionEvent(
        String bidId,
        String userId,
        String campaignId,
        String slotId,
        Instant timestamp
) {}
