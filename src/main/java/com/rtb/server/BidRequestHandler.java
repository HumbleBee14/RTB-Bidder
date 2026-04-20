package com.rtb.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.event.EventPublisher;
import com.rtb.event.events.BidEvent;
import com.rtb.frequency.FrequencyCapper;
import com.rtb.metrics.BidMetrics;
import com.rtb.model.AdCandidate;
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

/** Handles bid requests — the hot path. */
public final class BidRequestHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(BidRequestHandler.class);

    private final ObjectMapper objectMapper;
    private final BidPipeline pipeline;
    private final FrequencyCapper frequencyCapper;
    private final EventPublisher eventPublisher;
    private final BidMetrics bidMetrics;
    private final ExecutorService postResponseExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "post-response"));

    public BidRequestHandler(ObjectMapper objectMapper, BidPipeline pipeline,
                             FrequencyCapper frequencyCapper, EventPublisher eventPublisher,
                             BidMetrics bidMetrics) {
        this.objectMapper = objectMapper;
        this.pipeline = pipeline;
        this.frequencyCapper = frequencyCapper;
        this.eventPublisher = eventPublisher;
        this.bidMetrics = bidMetrics;
    }

    @Override
    public void handle(RoutingContext ctx) {
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        bidMetrics.recordRequest();

        try {
            Buffer body = ctx.body().buffer();
            if (body == null || body.length() == 0) {
                noBid(ctx, requestId, null, NoBidReason.INTERNAL_ERROR, startNanos);
                return;
            }

            BidRequest request = objectMapper.readValue(body.getBytes(), BidRequest.class);
            BidContext bidCtx = pipeline.execute(request, startNanos);

            if (bidCtx.isAborted()) {
                noBid(ctx, requestId, request.userId(), bidCtx.getNoBidReason(), startNanos);
                return;
            }

            if (bidCtx.getResponse() == null) {
                noBid(ctx, requestId, request.userId(), NoBidReason.INTERNAL_ERROR, startNanos);
                return;
            }

            String responseJson = objectMapper.writeValueAsString(bidCtx.getResponse());
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(responseJson);

            long latencyNanos = System.nanoTime() - startNanos;
            long latencyMs = latencyNanos / 1_000_000;
            bidMetrics.recordBid(latencyNanos);

            // ALL post-response work offloaded from event loop — Redis + Kafka never block HTTP
            String userId = request.userId();
            var slotWinners = bidCtx.getSlotWinners();
            var bids = bidCtx.getResponse().bids();
            postResponseExecutor.submit(() -> {
                for (AdCandidate winner : slotWinners.values()) {
                    frequencyCapper.recordImpression(userId, winner.getCampaign().id());
                }
                List<BidEvent.SlotBidInfo> slotBids = bids.stream()
                        .map(b -> new BidEvent.SlotBidInfo(b.slotId(), b.adId(), b.price()))
                        .toList();
                eventPublisher.publishBid(BidEvent.bid(requestId, userId, slotBids, latencyMs));
            });

        } catch (Exception e) {
            logger.error("Failed to process bid request", e);
            noBid(ctx, requestId, null, NoBidReason.INTERNAL_ERROR, startNanos);
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
