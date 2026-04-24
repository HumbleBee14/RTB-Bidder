package com.rtb.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.event.EventPublisher;
import com.rtb.event.events.WinEvent;
import com.rtb.metrics.BidMetrics;
import com.rtb.model.WinNotification;
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

    public WinHandler(ObjectMapper objectMapper, EventPublisher eventPublisher, BidMetrics bidMetrics) {
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.bidMetrics = bidMetrics;
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

            logger.info("Win: bidId={}, campaignId={}, clearingPrice={}",
                    notification.bidId(), notification.campaignId(), notification.clearingPrice());

            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end("{\"status\":\"acknowledged\"}");

            eventPublisher.publishWin(new WinEvent(
                    notification.bidId(), notification.campaignId(),
                    notification.clearingPrice(), Instant.now()));
            bidMetrics.recordWin();
            bidMetrics.recordCampaignWin(notification.campaignId());

        } catch (Exception e) {
            logger.error("Failed to process win notification", e);
            ctx.response().setStatusCode(400).end();
        }
    }
}
