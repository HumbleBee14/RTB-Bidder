package com.rtb.pipeline.stages;

import com.rtb.frequency.FrequencyCapper;
import com.rtb.model.AdCandidate;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineException;
import com.rtb.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filters out campaigns the user has already seen too many times.
 *
 * Read-only check — does not increment any counter. The increment happens
 * in BidRequestHandler's post-response work, only for the winning campaign.
 *
 * Phase 17 Slice 5: batch all candidates through one Redis MGET call instead
 * of per-candidate GETs. Cuts per-bid Redis time from ~28ms to ~0.2ms when
 * targeting matches hundreds of campaigns.
 */
public final class FrequencyCapStage implements PipelineStage {

    private final FrequencyCapper frequencyCapper;

    public FrequencyCapStage(FrequencyCapper frequencyCapper) {
        this.frequencyCapper = frequencyCapper;
    }

    @Override
    public void process(BidContext ctx) {
        String userId = ctx.getRequest().userId();
        List<AdCandidate> candidates = ctx.getCandidates();

        // Build campaignId → maxImpressions map for the batch call.
        // HashMap with explicit capacity avoids rehashing for the common ~250-key case.
        Map<String, Integer> campaignCaps = new HashMap<>(candidates.size() * 2);
        for (AdCandidate candidate : candidates) {
            campaignCaps.put(
                    candidate.getCampaign().id(),
                    candidate.getCampaign().maxImpressionsPerHour()
            );
        }

        Set<String> allowedIds;
        try {
            allowedIds = frequencyCapper.allowedCampaignIds(userId, campaignCaps);
        } catch (Exception e) {
            throw new PipelineException("Frequency cap check failed", e);
        }

        if (allowedIds.isEmpty()) {
            ctx.abort(NoBidReason.ALL_FREQUENCY_CAPPED);
            return;
        }

        List<AdCandidate> allowed = new ArrayList<>(allowedIds.size());
        for (AdCandidate candidate : candidates) {
            if (allowedIds.contains(candidate.getCampaign().id())) {
                allowed.add(candidate);
            }
        }
        ctx.setCandidates(allowed);
    }

    @Override
    public String name() {
        return "FrequencyCap";
    }
}
