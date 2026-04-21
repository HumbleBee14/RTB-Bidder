package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.BidRequest;
import com.rtb.model.BidResponse;
import com.rtb.model.Campaign;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

        String userId = ctx.getRequest().userId();

        List<BidResponse.SlotBid> bids = new ArrayList<>(slotWinners.size());
        for (Map.Entry<BidRequest.AdSlot, AdCandidate> entry : slotWinners.entrySet()) {
            BidRequest.AdSlot slot = entry.getKey();
            AdCandidate winner = entry.getValue();
            Campaign campaign = winner.getCampaign();

            double bidPrice = Math.max(campaign.bidFloor(), slot.bidFloor());
            String bidId = UUID.randomUUID().toString();

            // Pick the first matching creative size for this slot
            String matchedSize = findMatchingSize(campaign, slot);
            int[] dimensions = parseSize(matchedSize);

            // Tracking URLs embed user/campaign/slot IDs as query params —
            // standard ad-tech pattern, so TrackingHandler can publish complete events
            // without needing a bid-cache lookup on the hot tracking path.
            String trackingParams = "bid_id=" + urlEncode(bidId)
                    + "&user_id=" + urlEncode(userId)
                    + "&campaign_id=" + urlEncode(campaign.id())
                    + "&slot_id=" + urlEncode(slot.id());

            bids.add(new BidResponse.SlotBid(
                    bidId,
                    slot.id(),
                    campaign.id(),
                    bidPrice,
                    dimensions[0],
                    dimensions[1],
                    campaign.creativeUrl(),
                    new BidResponse.TrackingUrls(
                            baseUrl + "/impression?" + trackingParams,
                            baseUrl + "/click?" + trackingParams
                    ),
                    campaign.advertiserDomain()
            ));
        }

        ctx.setResponse(new BidResponse(bids));
    }

    private static String urlEncode(String value) {
        // Don't silently emit empty strings — RequestValidationStage guarantees userId/slotId
        // are non-blank, and campaign IDs are non-null from the repository. A null here
        // means a programming bug upstream; fail fast instead of hiding it in analytics.
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Cannot build tracking URL: required ID is null or blank");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String findMatchingSize(Campaign campaign, BidRequest.AdSlot slot) {
        for (String size : slot.sizes()) {
            if (campaign.creativeSizes().contains(size)) {
                return size;
            }
        }
        return null;
    }

    /** Parses "300x250" → {300, 250}. Returns {0, 0} on invalid format. */
    private int[] parseSize(String size) {
        if (size == null || !size.contains("x")) {
            return new int[]{0, 0};
        }
        try {
            String[] parts = size.split("x");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return new int[]{0, 0};
        }
    }

    @Override
    public String name() {
        return "ResponseBuild";
    }
}
