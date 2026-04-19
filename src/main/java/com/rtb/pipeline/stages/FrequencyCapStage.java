package com.rtb.pipeline.stages;

import com.rtb.frequency.FrequencyCapper;
import com.rtb.model.AdCandidate;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineException;
import com.rtb.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.List;

/** Filters out campaigns the user has seen too many times. Read-only check — does not increment. */
public final class FrequencyCapStage implements PipelineStage {

    private final FrequencyCapper frequencyCapper;

    public FrequencyCapStage(FrequencyCapper frequencyCapper) {
        this.frequencyCapper = frequencyCapper;
    }

    @Override
    public void process(BidContext ctx) {
        String userId = ctx.getRequest().userId();
        List<AdCandidate> candidates = ctx.getCandidates();
        List<AdCandidate> allowed = new ArrayList<>();

        try {
            for (AdCandidate candidate : candidates) {
                int maxImpressions = candidate.getCampaign().maxImpressionsPerHour();
                if (frequencyCapper.isAllowed(userId, candidate.getCampaign().id(), maxImpressions)) {
                    allowed.add(candidate);
                }
            }
        } catch (Exception e) {
            throw new PipelineException("Frequency cap check failed", e);
        }

        if (allowed.isEmpty()) {
            ctx.abort(NoBidReason.ALL_FREQUENCY_CAPPED);
            return;
        }

        ctx.setCandidates(allowed);
    }

    @Override
    public String name() {
        return "FrequencyCap";
    }
}
