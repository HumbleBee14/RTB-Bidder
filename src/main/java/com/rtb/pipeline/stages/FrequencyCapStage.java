package com.rtb.pipeline.stages;

import com.rtb.frequency.FrequencyCapper;
import com.rtb.model.AdCandidate;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineException;
import com.rtb.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.Comparator;
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
 * Production rationale:
 *   We almost never need Redis verdicts for every matched campaign. The bidder
 *   serves at most a handful of slot winners, and under realistic traffic the
 *   best-scoring candidates are rarely already capped. So we score first, then
 *   ask Redis about small score-ordered pages until we have enough allowed
 *   candidates for ranking. This keeps frequency-capping correct while avoiding
 *   giant MGET payloads on the hot path.
 */
public final class FrequencyCapStage implements PipelineStage {

    private static final Comparator<AdCandidate> BY_SCORE_DESC =
            Comparator.comparingDouble(AdCandidate::getScore).reversed();

    private final FrequencyCapper frequencyCapper;
    private final int batchSize;
    private final int keepTopAllowed;

    public FrequencyCapStage(FrequencyCapper frequencyCapper, int batchSize, int keepTopAllowed) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("pipeline.frequencycap.batchSize must be >= 1, got: " + batchSize);
        }
        if (keepTopAllowed < 1) {
            throw new IllegalArgumentException("pipeline.frequencycap.keepTopAllowed must be >= 1, got: " + keepTopAllowed);
        }
        this.frequencyCapper = frequencyCapper;
        this.batchSize = batchSize;
        this.keepTopAllowed = keepTopAllowed;
    }

    @Override
    public void process(BidContext ctx) {
        String userId = ctx.getRequest().userId();
        List<AdCandidate> candidates = ctx.getCandidates();
        if (candidates.isEmpty()) {
            ctx.abort(NoBidReason.ALL_FREQUENCY_CAPPED);
            return;
        }

        ArrayList<AdCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(BY_SCORE_DESC);

        int targetAllowed = Math.min(keepTopAllowed, ranked.size());
        List<AdCandidate> allowed = new ArrayList<>(targetAllowed);

        for (int start = 0; start < ranked.size() && allowed.size() < targetAllowed; start += batchSize) {
            int end = Math.min(start + batchSize, ranked.size());
            Map<String, Integer> campaignCaps = new HashMap<>((end - start) * 2);
            for (int i = start; i < end; i++) {
                AdCandidate candidate = ranked.get(i);
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

            for (int i = start; i < end && allowed.size() < targetAllowed; i++) {
                AdCandidate candidate = ranked.get(i);
                if (allowedIds.contains(candidate.getCampaign().id())) {
                    allowed.add(candidate);
                }
            }
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
