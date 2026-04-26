package com.rtb.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.rtb.codec.BidRequestCodec;
import com.rtb.codec.BidResponseCodec;
import com.rtb.event.EventPublisher;
import com.rtb.event.events.BidEvent;
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

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Handles bid requests — the hot path.
 *
 * Threading model (Phase 17):
 *   Event-loop thread : accept request → parse JSON (~50µs, CPU only) → send response
 *   Worker thread     : full 8-stage pipeline including blocking Redis SMEMBERS (~1ms)
 *   Background worker : bounded write-behind queue for freq-cap writes
 *
 * Separating parse/send from blocking I/O keeps the event loop free to accept the
 * next connection immediately. Throughput ceiling becomes worker-pool-size / pipeline-latency
 * rather than 1 / pipeline-latency.
 *
 * With the Phase 17 Slice 3 segment cache, most pipeline calls skip Redis entirely
 * (cache-hit latency ~0.1ms), so the effective ceiling is roughly 10× higher again.
 */
public final class BidRequestHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(BidRequestHandler.class);

    private final BidPipeline pipeline;
    private final EventPublisher eventPublisher;
    private final BidMetrics bidMetrics;
    private final BidRequestCodec requestCodec;
    private final BidResponseCodec responseCodec;
    private final ImpressionRecorder impressionRecorder;
    private final JsonFactory jsonFactory;

    public BidRequestHandler(BidPipeline pipeline,
                             EventPublisher eventPublisher, BidMetrics bidMetrics,
                             ImpressionRecorder impressionRecorder) {
        this.pipeline = pipeline;
        this.eventPublisher = eventPublisher;
        this.bidMetrics = bidMetrics;
        this.impressionRecorder = impressionRecorder;
        this.jsonFactory = new JsonFactory();
        this.requestCodec = new BidRequestCodec();
        this.responseCodec = new BidResponseCodec(jsonFactory);
    }

    @Override
    public void handle(RoutingContext ctx) {
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        bidMetrics.recordRequest();

        // Parse stays on the event loop — pure CPU work, no I/O (~50µs)
        Buffer body = ctx.body().buffer();
        if (body == null || body.length() == 0) {
            noBid(ctx, requestId, null, NoBidReason.INTERNAL_ERROR, startNanos);
            return;
        }

        final BidRequest request;
        try {
            request = parseRequest(body);
        } catch (Exception e) {
            logger.error("Failed to parse bid request", e);
            noBid(ctx, requestId, null, NoBidReason.INTERNAL_ERROR, startNanos);
            return;
        }

        // Pipeline has blocking Redis I/O — offload to Vert.x worker pool.
        // false = unordered: parallel calls never need to be serialised relative to
        // each other. This allows all worker threads to execute concurrently.
        ctx.vertx().<BidContext>executeBlocking(() -> pipeline.execute(request, startNanos), false)
                .onSuccess(bidCtx -> finishRequest(ctx, requestId, request, startNanos, bidCtx))
                .onFailure(err -> {
                    logger.error("Pipeline worker failed unexpectedly", err);
                    noBid(ctx, requestId, request.userId(), NoBidReason.INTERNAL_ERROR, startNanos);
                });
    }

    // Called on the event loop thread after the worker thread returns the BidContext.
    private void finishRequest(RoutingContext ctx, String requestId, BidRequest request,
                               long startNanos, BidContext result) {
        // toRelease tracks whether we still owe the pool a release.
        // Cleared only when we hand the context back (pipeline.release) or confirm abort.
        BidContext toRelease = result;
        try {
            if (result.isAborted()) {
                noBid(ctx, requestId, request.userId(), result.getNoBidReason(), startNanos);
                return;
            }

            if (result.getResponse() == null) {
                noBid(ctx, requestId, request.userId(), NoBidReason.INTERNAL_ERROR, startNanos);
                return;
            }

            byte[] responseBytes = responseCodec.encode(result.getResponse());
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(Buffer.buffer(responseBytes));

            long latencyNanos = System.nanoTime() - startNanos;
            long latencyMs = latencyNanos / 1_000_000;
            bidMetrics.recordBid(latencyNanos);

            String userId = request.userId();
            List<Winner> winners = result.getSlotWinners().values().stream()
                    .map(w -> new Winner(userId, w.getCampaign().id()))
                    .toList();
            for (Winner w : winners) {
                bidMetrics.recordCampaignBid(w.campaignId());
            }
            List<BidEvent.SlotBidInfo> slotBids = result.getResponse().bids().stream()
                    .map(b -> new BidEvent.SlotBidInfo(b.slotId(), b.adId(), b.price()))
                    .toList();

            // Release back to pool before post-response work — context is no longer needed
            pipeline.release(result);
            toRelease = null;

            // Redis writes go through a bounded write-behind queue so a burst cannot
            // create unbounded background pressure. Kafka publish is already async in
            // KafkaEventPublisher, so this call only enqueues into that publisher.
            for (Winner w : winners) {
                impressionRecorder.record(w.userId(), w.campaignId());
            }
            eventPublisher.publishBid(BidEvent.bid(requestId, userId, slotBids, latencyMs));

        } catch (Exception e) {
            logger.error("Failed to finish bid request", e);
            noBid(ctx, requestId, request.userId(), NoBidReason.INTERNAL_ERROR, startNanos);
        } finally {
            if (toRelease != null) {
                pipeline.release(toRelease);
            }
        }
    }

    private BidRequest parseRequest(Buffer body) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(
                body.getByteBuf().array(), body.getByteBuf().arrayOffset(),
                body.getByteBuf().readableBytes())) {
            return requestCodec.parse(parser);
        } catch (UnsupportedOperationException e) {
            // Direct buffer without array backing — fall back to byte-copy
            try (JsonParser parser = jsonFactory.createParser(body.getBytes())) {
                return requestCodec.parse(parser);
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

    /** Typed carrier for (userId, campaignId) pairs — extracted before context is released. */
    private record Winner(String userId, String campaignId) {}
}
