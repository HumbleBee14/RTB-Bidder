package com.rtb.pipeline.stages;

import com.rtb.model.BidRequest;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

/** Validates required fields. Aborts with NO_MATCHING_CAMPAIGN on invalid requests. */
public final class RequestValidationStage implements PipelineStage {

    @Override
    public void process(BidContext ctx) {
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
                ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
                return;
            }
            if (slot.bidFloor() < 0) {
                ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
                return;
            }
        }
    }

    @Override
    public String name() {
        return "RequestValidation";
    }
}
