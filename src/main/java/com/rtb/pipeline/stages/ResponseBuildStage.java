package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.BidRequest;
import com.rtb.model.BidResponse;
import com.rtb.model.Campaign;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds a BidResponse with one bid per slot that has a winner. */
public final class ResponseBuildStage implements PipelineStage {

    private final String baseUrl;

    public ResponseBuildStage(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public void process(BidContext ctx) {
        Map<BidRequest.AdSlot, AdCandidate> slotWinners = ctx.getSlotWinners();
        if (slotWinners.isEmpty()) {
            ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
            return;
        }

        List<BidResponse.SlotBid> bids = new ArrayList<>(slotWinners.size());
        for (Map.Entry<BidRequest.AdSlot, AdCandidate> entry : slotWinners.entrySet()) {
            BidRequest.AdSlot slot = entry.getKey();
            AdCandidate winner = entry.getValue();
            Campaign campaign = winner.getCampaign();

            double bidPrice = Math.max(campaign.bidFloor(), slot.bidFloor());
            String bidId = UUID.randomUUID().toString();

            // Pick the first matching creative size for this slot
            String matchedSize = findMatchingSize(campaign, slot);
            int width = 0;
            int height = 0;
            if (matchedSize != null && matchedSize.contains("x")) {
                String[] parts = matchedSize.split("x");
                width = Integer.parseInt(parts[0]);
                height = Integer.parseInt(parts[1]);
            }

            bids.add(new BidResponse.SlotBid(
                    bidId,
                    slot.id(),
                    campaign.id(),
                    bidPrice,
                    width,
                    height,
                    campaign.creativeUrl(),
                    new BidResponse.TrackingUrls(
                            baseUrl + "/impression?bid_id=" + bidId,
                            baseUrl + "/click?bid_id=" + bidId
                    ),
                    campaign.advertiserDomain()
            ));
        }

        ctx.setResponse(new BidResponse(bids));
    }

    private String findMatchingSize(Campaign campaign, BidRequest.AdSlot slot) {
        for (String size : slot.sizes()) {
            if (campaign.creativeSizes().contains(size)) {
                return size;
            }
        }
        return null;
    }

    @Override
    public String name() {
        return "ResponseBuild";
    }
}
