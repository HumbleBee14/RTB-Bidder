package com.rtb.event.events;

import java.time.Instant;

/** Published when the user clicks the ad. */
public record ClickEvent(
        String bidId,
        Instant timestamp
) {}
