package com.rtb.server;

import com.rtb.event.EventPublisher;
import com.rtb.event.events.ClickEvent;
import com.rtb.event.events.ImpressionEvent;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;

/** Handles impression and click tracking. */
public final class TrackingHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingHandler.class);

    private static final Buffer TRACKING_PIXEL = Buffer.buffer(
            Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    );

    private final EventPublisher eventPublisher;

    public TrackingHandler(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void handleImpression(RoutingContext ctx) {
        String bidId = ctx.request().getParam("bid_id");
        if (bidId == null || bidId.isBlank()) {
            ctx.response().setStatusCode(400).end();
            return;
        }

        // User/campaign/slot IDs come from query params — ResponseBuildStage embeds these
        // in the tracking URLs. Avoids a bid-cache lookup on the tracking hot path.
        String userId = ctx.request().getParam("user_id");
        String campaignId = ctx.request().getParam("campaign_id");
        String slotId = ctx.request().getParam("slot_id");

        logger.info("Impression: bidId={} campaignId={}", bidId, campaignId);

        ctx.response()
                .putHeader("Content-Type", "image/gif")
                .putHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                .setStatusCode(200)
                .end(TRACKING_PIXEL);

        eventPublisher.publishImpression(new ImpressionEvent(bidId, userId, campaignId, slotId, Instant.now()));
    }

    public void handleClick(RoutingContext ctx) {
        String bidId = ctx.request().getParam("bid_id");
        if (bidId == null || bidId.isBlank()) {
            ctx.response().setStatusCode(400).end();
            return;
        }

        String userId = ctx.request().getParam("user_id");
        String campaignId = ctx.request().getParam("campaign_id");
        String slotId = ctx.request().getParam("slot_id");

        logger.info("Click: bidId={} campaignId={}", bidId, campaignId);

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end("{\"status\":\"click_tracked\",\"bid_id\":\"" + bidId.replace("\"", "\\\"") + "\"}");

        eventPublisher.publishClick(new ClickEvent(bidId, userId, campaignId, slotId, Instant.now()));
    }
}
