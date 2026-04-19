package com.rtb.model;

/** Request context used for contextual targeting — app, device, geo. */
public record AdContext(
        String appCategory,
        String deviceType,
        String deviceOs,
        String geo
) {
    public static AdContext from(BidRequest request) {
        BidRequest.App app = request.app();
        BidRequest.Device device = request.device();
        return new AdContext(
                app != null ? app.category() : null,
                device != null ? device.type() : null,
                device != null ? device.os() : null,
                device != null ? device.geo() : null
        );
    }
}
