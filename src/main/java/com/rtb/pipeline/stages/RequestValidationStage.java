package com.rtb.pipeline.stages;

import com.rtb.model.BidRequest;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineException;
import com.rtb.pipeline.PipelineStage;

/** Validates required fields. Aborts pipeline on malformed requests. */
public final class RequestValidationStage implements PipelineStage {

    @Override
    public void process(BidContext ctx) throws PipelineException {
        BidRequest request = ctx.getRequest();

        if (request.userId() == null || request.userId().isBlank()) {
            ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
            return;
        }

        if (request.adSlots() == null || request.adSlots().isEmpty()) {
            ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
            return;
        }

        for (BidRequest.AdSlot slot : request.adSlots()) {
            if (slot.id() == null || slot.id().isBlank()) {
                throw new PipelineException("Ad slot missing id");
            }
            if (slot.bidFloor() < 0) {
                throw new PipelineException("Negative bid floor: " + slot.bidFloor());
            }
        }
    }

    @Override
    public String name() {
        return "RequestValidation";
    }
}
