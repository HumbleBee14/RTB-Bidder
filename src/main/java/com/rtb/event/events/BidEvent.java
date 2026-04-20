package com.rtb.event.events;

import java.time.Instant;
import java.util.List;

/** Published after every bid request — whether we bid or not. */
public record BidEvent(
        String requestId,
        String userId,
        boolean bid,
        String noBidReason,
        List<SlotBidInfo> slotBids,
        long pipelineLatencyMs,
        Instant timestamp
) {
    public record SlotBidInfo(String slotId, String campaignId, double price) {}

    public static BidEvent bid(String requestId, String userId, List<SlotBidInfo> slotBids, long latencyMs) {
        return new BidEvent(requestId, userId, true, null, slotBids, latencyMs, Instant.now());
    }

    public static BidEvent noBid(String requestId, String userId, String reason, long latencyMs) {
        return new BidEvent(requestId, userId, false, reason, List.of(), latencyMs, Instant.now());
    }
}
