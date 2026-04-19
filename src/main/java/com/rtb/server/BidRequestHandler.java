package com.rtb.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.model.BidRequest;
import com.rtb.model.BidResponse;
import com.rtb.model.NoBidReason;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Handles bid requests — the hot path. */
public final class BidRequestHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(BidRequestHandler.class);

    private final ObjectMapper objectMapper;

    public BidRequestHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(RoutingContext ctx) {
        long startNanos = System.nanoTime();

        try {
            Buffer body = ctx.body().buffer();
            if (body == null || body.length() == 0) {
                noBid(ctx, NoBidReason.INTERNAL_ERROR, startNanos);
                return;
            }

            BidRequest request = objectMapper.readValue(body.getBytes(), BidRequest.class);

            if (!isValid(request)) {
                noBid(ctx, NoBidReason.NO_MATCHING_CAMPAIGN, startNanos);
                return;
            }

            // TODO: replace with BidPipeline.process(request) in Phase 2
            BidRequest.AdSlot firstSlot = request.adSlots().get(0);
            String bidId = UUID.randomUUID().toString();

            BidResponse response = new BidResponse(
                    bidId,
                    "ad-001",
                    firstSlot.bidFloor() + 0.10,
                    "https://ads.example.com/creative/ad-001.html",
                    new BidResponse.TrackingUrls(
                            "http://localhost:8080/impression?bid_id=" + bidId,
                            "http://localhost:8080/click?bid_id=" + bidId
                    ),
                    "example.com"
            );

            String responseJson = objectMapper.writeValueAsString(response);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(responseJson);

            logger.info("Bid: bidId={}, price={}, latencyMs={}", bidId, response.price(), latencyMs);

        } catch (Exception e) {
            logger.error("Failed to process bid request", e);
            noBid(ctx, NoBidReason.INTERNAL_ERROR, startNanos);
        }
    }

    private boolean isValid(BidRequest request) {
        return request.userId() != null
                && !request.userId().isBlank()
                && request.adSlots() != null
                && !request.adSlots().isEmpty();
    }

    private void noBid(RoutingContext ctx, NoBidReason reason, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        ctx.response()
                .putHeader("X-NoBid-Reason", reason.name())
                .setStatusCode(204)
                .end();
        logger.info("No-bid: reason={}, latencyMs={}", reason, latencyMs);
    }
}
