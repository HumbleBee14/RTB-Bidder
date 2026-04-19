package com.rtb.model;

import java.util.List;

/** OpenRTB-like bid request. */
public record BidRequest(
        String userId,
        App app,
        Device device,
        List<AdSlot> adSlots
) {

    public record App(
            String id,
            String category,
            String bundle
    ) {}

    public record Device(
            String type,
            String os,
            String geo
    ) {}

    public record AdSlot(
            String id,
            List<String> sizes,
            double bidFloor
    ) {}
}
