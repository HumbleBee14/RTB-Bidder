package com.rtb.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.rtb.codec.BidRequestCodec;
import com.rtb.codec.BidResponseCodec;
import com.rtb.event.EventPublisher;
import com.rtb.event.events.BidEvent;
import com.rtb.frequency.FrequencyCapper;
import com.rtb.metrics.BidMetrics;
import com.rtb.model.BidRequest;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.BidPipeline;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Handles bid requests — the hot path. Uses streaming codecs for zero-alloc JSON. */
public final class BidRequestHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(BidRequestHandler.class);

    private final BidPipeline pipeline;
    private final FrequencyCapper frequencyCapper;
    private final EventPublisher eventPublisher;
    private final BidMetrics bidMetrics;
    private final BidRequestCodec requestCodec;
    private final BidResponseCodec responseCodec;
    private final JsonFactory jsonFactory;
    private final ExecutorService postResponseExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "post-response"));

    public BidRequestHandler(BidPipeline pipeline, FrequencyCapper frequencyCapper,
                             EventPublisher eventPublisher, BidMetrics bidMetrics) {
        this.pipeline = pipeline;
        this.frequencyCapper = frequencyCapper;
        this.eventPublisher = eventPublisher;
        this.bidMetrics = bidMetrics;
        this.jsonFactory = new JsonFactory();
        this.requestCodec = new BidRequestCodec();
        this.responseCodec = new BidResponseCodec(jsonFactory);
    }

    @Override
    public void handle(RoutingContext ctx) {
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        bidMetrics.recordRequest();

        BidContext bidCtx = null;
        try {
            Buffer body = ctx.body().buffer();
            if (body == null || body.length() == 0) {
                noBid(ctx, requestId, null, NoBidReason.INTERNAL_ERROR, startNanos);
                return;
            }

            // Streaming parse — token-by-token, no ObjectMapper tree
            BidRequest request;
            try (JsonParser parser = jsonFactory.createParser(
                    body.getByteBuf().array(), body.getByteBuf().arrayOffset(),
                    body.getByteBuf().readableBytes())) {
                request = requestCodec.parse(parser);
            } catch (UnsupportedOperationException e) {
                // Direct buffer without array backing — fall back to getBytes()
                try (JsonParser parser = jsonFactory.createParser(body.getBytes())) {
                    request = requestCodec.parse(parser);
                }
            }

            bidCtx = pipeline.execute(request, startNanos);

            if (bidCtx.isAborted()) {
                noBid(ctx, requestId, request.userId(), bidCtx.getNoBidReason(), startNanos);
                return;
            }

            if (bidCtx.getResponse() == null) {
                noBid(ctx, requestId, request.userId(), NoBidReason.INTERNAL_ERROR, startNanos);
                return;
            }

            // Streaming write — no ObjectMapper, no intermediate String
            byte[] responseBytes = responseCodec.encode(bidCtx.getResponse());
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(Buffer.buffer(responseBytes));

            long latencyNanos = System.nanoTime() - startNanos;
            long latencyMs = latencyNanos / 1_000_000;
            bidMetrics.recordBid(latencyNanos);

            // Extract data needed for post-response BEFORE releasing context to pool
            String userId = request.userId();
            List<String[]> winnerIds = bidCtx.getSlotWinners().values().stream()
                    .map(w -> new String[]{userId, w.getCampaign().id()})
                    .toList();
            List<BidEvent.SlotBidInfo> slotBids = bidCtx.getResponse().bids().stream()
                    .map(b -> new BidEvent.SlotBidInfo(b.slotId(), b.adId(), b.price()))
                    .toList();

            // Release context to pool immediately — don't hold it for post-response
            pipeline.release(bidCtx);
            bidCtx = null;

            // Post-response work uses extracted data only — no context reference
            postResponseExecutor.submit(() -> {
                for (String[] ids : winnerIds) {
                    frequencyCapper.recordImpression(ids[0], ids[1]);
                }
                eventPublisher.publishBid(BidEvent.bid(requestId, userId, slotBids, latencyMs));
            });

        } catch (Exception e) {
            logger.error("Failed to process bid request", e);
            noBid(ctx, requestId, null, NoBidReason.INTERNAL_ERROR, startNanos);
        } finally {
            // Release context if not handed to post-response thread
            if (bidCtx != null) {
                pipeline.release(bidCtx);
            }
        }
    }

    private void noBid(RoutingContext ctx, String requestId, String userId,
                       NoBidReason reason, long startNanos) {
        long latencyNanos = System.nanoTime() - startNanos;
        long latencyMs = latencyNanos / 1_000_000;
        bidMetrics.recordNoBid(reason.name(), latencyNanos);
        if (reason == NoBidReason.ALL_FREQUENCY_CAPPED) bidMetrics.recordFrequencyCapHit();
        if (reason == NoBidReason.BUDGET_EXHAUSTED) bidMetrics.recordBudgetExhausted();
        ctx.response()
                .putHeader("X-NoBid-Reason", reason.name())
                .setStatusCode(204)
                .end();
        logger.info("No-bid: reason={}, latencyMs={}", reason, latencyMs);
        eventPublisher.publishBid(BidEvent.noBid(requestId, userId, reason.name(), latencyMs));
    }
}
