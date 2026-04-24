package com.rtb.server;

import com.rtb.event.EventPublisher;
import com.rtb.event.events.ClickEvent;
import com.rtb.event.events.ImpressionEvent;
import com.rtb.metrics.BidMetrics;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;

/**
 * Handles impression and click tracking.
 *
 * Tracking endpoints are public — anyone with the URL can hit them. We validate inputs
 * (length caps, safe charset) to prevent log-forging and analytics poisoning via malformed
 * IDs. Production hardening would add HMAC signatures on the URL params so only the exchange
 * can produce valid tracking URLs — see phase-8 docs "Future enhancement: signed tracking URLs".
 */
public final class TrackingHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrackingHandler.class);

    private static final Buffer TRACKING_PIXEL = Buffer.buffer(
            Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    );

    // Length cap — our IDs are short UUIDs or short strings, never close to this.
    // Rejecting longer values blocks log-flood / memory-exhaustion attempts via giant params.
    private static final int MAX_ID_LENGTH = 128;

    // Allow-list: alphanumeric, dash, underscore, dot, colon (UUIDs, prefixed IDs, etc).
    // Blocks control chars (log forging via \r\n), path separators, quotes, SQL-ish chars.
    private static final java.util.regex.Pattern ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final EventPublisher eventPublisher;
    private final BidMetrics bidMetrics;

    public TrackingHandler(EventPublisher eventPublisher, BidMetrics bidMetrics) {
        this.eventPublisher = eventPublisher;
        this.bidMetrics = bidMetrics;
    }

    public void handleImpression(RoutingContext ctx) {
        String bidId = validateId(ctx.request().getParam("bid_id"));
        if (bidId == null) { ctx.response().setStatusCode(400).end(); return; }

        String userId = validateId(ctx.request().getParam("user_id"));
        String campaignId = validateId(ctx.request().getParam("campaign_id"));
        String slotId = validateId(ctx.request().getParam("slot_id"));
        if (userId == null || campaignId == null || slotId == null) {
            ctx.response().setStatusCode(400).end();
            return;
        }

        logger.info("Impression: bidId={} campaignId={}", bidId, campaignId);

        ctx.response()
                .putHeader("Content-Type", "image/gif")
                .putHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                .setStatusCode(200)
                .end(TRACKING_PIXEL);

        eventPublisher.publishImpression(new ImpressionEvent(bidId, userId, campaignId, slotId, Instant.now()));
        bidMetrics.recordImpression();
    }

    public void handleClick(RoutingContext ctx) {
        String bidId = validateId(ctx.request().getParam("bid_id"));
        if (bidId == null) { ctx.response().setStatusCode(400).end(); return; }

        String userId = validateId(ctx.request().getParam("user_id"));
        String campaignId = validateId(ctx.request().getParam("campaign_id"));
        String slotId = validateId(ctx.request().getParam("slot_id"));
        if (userId == null || campaignId == null || slotId == null) {
            ctx.response().setStatusCode(400).end();
            return;
        }

        logger.info("Click: bidId={} campaignId={}", bidId, campaignId);

        ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end("{\"status\":\"click_tracked\",\"bid_id\":\"" + bidId + "\"}");

        eventPublisher.publishClick(new ClickEvent(bidId, userId, campaignId, slotId, Instant.now()));
        bidMetrics.recordClick();
    }

    /** Returns the value if it's a safe, short ID; null otherwise. */
    private static String validateId(String value) {
        if (value == null || value.length() > MAX_ID_LENGTH) return null;
        return ID_PATTERN.matcher(value).matches() ? value : null;
    }
}
