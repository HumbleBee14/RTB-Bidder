package com.rtb.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.event.EventPublisher;
import com.rtb.event.events.WinEvent;
import com.rtb.metrics.BidMetrics;
import com.rtb.model.Campaign;
import com.rtb.model.WinNotification;
import com.rtb.repository.CampaignRepository;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/** Handles win notifications from the exchange. */
public final class WinHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(WinHandler.class);

    private final ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;
    private final BidMetrics bidMetrics;
    private final CampaignRepository campaignRepository;

    public WinHandler(ObjectMapper objectMapper, EventPublisher eventPublisher,
                      BidMetrics bidMetrics, CampaignRepository campaignRepository) {
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.bidMetrics = bidMetrics;
        this.campaignRepository = campaignRepository;
    }

    /**
     * Campaign IDs come from external exchange input. Without validation, a
     * malicious or misconfigured caller could send arbitrary strings and
     * explode Prometheus cardinality via campaign_wins_total{campaign_id=...}.
     * Linear scan is fine — /win is low QPS and the campaign list is cached
     * in memory (typically 10-100 entries).
     */
    private boolean isKnownCampaign(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) return false;
        for (Campaign c : campaignRepository.getActiveCampaigns()) {
            if (campaignId.equals(c.id())) return true;
        }
        return false;
    }

    @Override
    public void handle(RoutingContext ctx) {
        try {
            Buffer body = ctx.body().buffer();
            if (body == null || body.length() == 0) {
                ctx.response().setStatusCode(400).end();
                return;
            }

            WinNotification notification = objectMapper.readValue(body.getBytes(), WinNotification.class);

            // Truncate externally-supplied IDs so attacker-padded strings can't
            // bloat log files or downstream log pipelines.
            logger.info("Win: bidId={}, campaignId={}, clearingPrice={}",
                    truncate(notification.bidId(), 64),
                    truncate(notification.campaignId(), 64),
                    notification.clearingPrice());

            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end("{\"status\":\"acknowledged\"}");

            eventPublisher.publishWin(new WinEvent(
                    notification.bidId(), notification.campaignId(),
                    notification.clearingPrice(), Instant.now()));
            bidMetrics.recordWin();
            // Only record per-campaign metric for known campaign IDs — blocks
            // unbounded-cardinality attacks via attacker-supplied campaign_id.
            if (isKnownCampaign(notification.campaignId())) {
                bidMetrics.recordCampaignWin(notification.campaignId());
            } else {
                // Don't log at WARN with the raw ID — an attacker flooding /win with
                // bogus IDs could amplify our log volume. Count it (cardinality 1) and
                // log at DEBUG with a truncated ID as defence in depth.
                bidMetrics.recordWinUnknownCampaign();
                if (logger.isDebugEnabled()) {
                    logger.debug("Win for unknown campaignId={} — metric dropped",
                            truncate(notification.campaignId(), 40));
                }
            }

        } catch (Exception e) {
            logger.error("Failed to process win notification", e);
            ctx.response().setStatusCode(400).end();
        }
    }

    /** Caps log-message length so attacker-supplied strings can't bloat log files. */
    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…(truncated)";
    }
}
