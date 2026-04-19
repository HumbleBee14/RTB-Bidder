package com.rtb.model;

/** OpenRTB-like bid response. */
public record BidResponse(
        String bidId,
        String adId,
        double price,
        String creativeUrl,
        TrackingUrls trackingUrls,
        String advertiserDomain
) {

    public record TrackingUrls(
            String impressionUrl,
            String clickUrl
    ) {}
}
