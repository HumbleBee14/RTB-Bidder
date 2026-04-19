package com.rtb.pipeline.stages;

import com.rtb.model.BidRequest;
import com.rtb.model.BidResponse;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineException;
import com.rtb.pipeline.PipelineStage;

import java.util.UUID;

/** Builds BidResponse from the pipeline context. */
public final class ResponseBuildStage implements PipelineStage {

    private final String baseUrl;

    public ResponseBuildStage(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public void process(BidContext ctx) throws PipelineException {
        BidRequest request = ctx.getRequest();
        // TODO: build from winning candidate once targeting/scoring is wired (Phase 4-6)
        BidRequest.AdSlot firstSlot = request.adSlots().get(0);
        String bidId = UUID.randomUUID().toString();

        BidResponse response = new BidResponse(
                bidId,
                "ad-001",
                firstSlot.bidFloor() + 0.10,
                "https://ads.example.com/creative/ad-001.html",
                new BidResponse.TrackingUrls(
                        baseUrl + "/impression?bid_id=" + bidId,
                        baseUrl + "/click?bid_id=" + bidId
                ),
                "example.com"
        );

        ctx.setResponse(response);
    }

    @Override
    public String name() {
        return "ResponseBuild";
    }
}
