package com.rtb.model;

import java.util.List;

/** OpenRTB-like bid response — one bid per slot that had a winning candidate. */
public record BidResponse(
        List<SlotBid> bids
) {
    /** A single bid for one ad slot. */
    public record SlotBid(
            String bidId,
            String slotId,
            String adId,
            double price,
            int width,
            int height,
            String creativeUrl,
            TrackingUrls trackingUrls,
            String advertiserDomain
    ) {}

    public record TrackingUrls(
            String impressionUrl,
            String clickUrl
    ) {}
}
