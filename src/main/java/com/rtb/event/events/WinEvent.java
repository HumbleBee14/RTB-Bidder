package com.rtb.event.events;

import java.time.Instant;

/** Published when the exchange notifies us our bid won. This is when billing happens. */
public record WinEvent(
        String bidId,
        String campaignId,
        double clearingPrice,
        Instant timestamp
) {}
