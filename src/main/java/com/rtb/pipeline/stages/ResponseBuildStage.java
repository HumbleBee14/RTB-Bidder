package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.BidResponse;
import com.rtb.model.Campaign;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

import java.util.UUID;

/** Builds BidResponse from the winner selected by RankingStage. */
public final class ResponseBuildStage implements PipelineStage {

    private final String baseUrl;

    public ResponseBuildStage(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public void process(BidContext ctx) {
        AdCandidate winner = ctx.getWinner();
        if (winner == null) {
            ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
            return;
        }

        Campaign campaign = winner.getCampaign();
        double exchangeFloor = ctx.getRequest().adSlots().get(0).bidFloor();
        double bidPrice = Math.max(campaign.bidFloor(), exchangeFloor);
        String bidId = UUID.randomUUID().toString();

        BidResponse response = new BidResponse(
                bidId,
                campaign.id(),
                bidPrice,
                campaign.creativeUrl(),
                new BidResponse.TrackingUrls(
                        baseUrl + "/impression?bid_id=" + bidId,
                        baseUrl + "/click?bid_id=" + bidId
                ),
                campaign.advertiserDomain()
        );

        ctx.setResponse(response);
    }

    @Override
    public String name() {
        return "ResponseBuild";
    }
}
