package com.rtb.model;

/** Win notification from the exchange. */
public record WinNotification(
        String bidId,
        String campaignId,
        double clearingPrice
) {}
