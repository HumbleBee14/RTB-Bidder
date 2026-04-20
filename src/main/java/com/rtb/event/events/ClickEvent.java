package com.rtb.event.events;

import java.time.Instant;

/** Published when the user clicks the ad. */
public record ClickEvent(
        String bidId,
        String userId,
        String campaignId,
        String slotId,
        Instant timestamp
) {}
