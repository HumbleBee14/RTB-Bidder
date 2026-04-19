package com.rtb.server;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/** Handles impression and click tracking. */
public final class TrackingHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingHandler.class);

    private static final Buffer TRACKING_PIXEL = Buffer.buffer(
            Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    );

    public void handleImpression(RoutingContext ctx) {
        String bidId = ctx.request().getParam("bid_id");
        if (bidId == null || bidId.isBlank()) {
            ctx.response().setStatusCode(400).end();
            return;
        }

        logger.info("Impression: bidId={}", bidId);

        ctx.response()
                .putHeader("Content-Type", "image/gif")
                .putHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                .setStatusCode(200)
                .end(TRACKING_PIXEL);
    }

    public void handleClick(RoutingContext ctx) {
        String bidId = ctx.request().getParam("bid_id");
        if (bidId == null || bidId.isBlank()) {
            ctx.response().setStatusCode(400).end();
            return;
        }

        logger.info("Click: bidId={}", bidId);

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end("{\"status\":\"click_tracked\",\"bid_id\":\"" + bidId + "\"}");
    }
}
