package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.NoBidReason;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;
import com.rtb.repository.CampaignRepository;
import com.rtb.targeting.TargetingEngine;

import java.util.List;

/** Retrieves matching campaigns for this user via the targeting engine. */
public final class CandidateRetrievalStage implements PipelineStage {

    private final CampaignRepository campaignRepository;
    private final TargetingEngine targetingEngine;

    public CandidateRetrievalStage(CampaignRepository campaignRepository, TargetingEngine targetingEngine) {
        this.campaignRepository = campaignRepository;
        this.targetingEngine = targetingEngine;
    }

    @Override
    public void process(BidContext ctx) {
        List<Campaign> campaigns = campaignRepository.getActiveCampaigns();
        AdContext adContext = AdContext.from(ctx.getRequest());
        List<AdCandidate> candidates = targetingEngine.match(campaigns, ctx.getUserProfile(), adContext);

        if (candidates.isEmpty()) {
            ctx.abort(NoBidReason.NO_MATCHING_CAMPAIGN);
            return;
        }

        ctx.setCandidates(candidates);
    }

    @Override
    public String name() {
        return "CandidateRetrieval";
    }
}
