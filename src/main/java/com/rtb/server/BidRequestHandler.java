package com.rtb.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.model.BidRequest;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.BidPipeline;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles bid requests — the hot path. */
public final class BidRequestHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(BidRequestHandler.class);

    private final ObjectMapper objectMapper;
    private final BidPipeline pipeline;

    public BidRequestHandler(ObjectMapper objectMapper, BidPipeline pipeline) {
        this.objectMapper = objectMapper;
        this.pipeline = pipeline;
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
            BidContext bidCtx = pipeline.execute(request, startNanos);

            if (bidCtx.isAborted()) {
                noBid(ctx, bidCtx.getNoBidReason(), startNanos);
                return;
            }

            if (bidCtx.getResponse() == null) {
                noBid(ctx, NoBidReason.INTERNAL_ERROR, startNanos);
                return;
            }

            String responseJson = objectMapper.writeValueAsString(bidCtx.getResponse());
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(responseJson);

        } catch (Exception e) {
            logger.error("Failed to process bid request", e);
            noBid(ctx, NoBidReason.INTERNAL_ERROR, startNanos);
        }
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
