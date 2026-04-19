package com.rtb.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.model.WinNotification;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles win notifications from the exchange. */
public final class WinHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(WinHandler.class);

    private final ObjectMapper objectMapper;

    public WinHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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

        } catch (Exception e) {
            logger.error("Failed to process win notification", e);
            ctx.response().setStatusCode(400).end();
        }
    }
}
