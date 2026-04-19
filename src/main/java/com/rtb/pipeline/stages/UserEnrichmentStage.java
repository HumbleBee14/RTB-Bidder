package com.rtb.pipeline.stages;

import com.rtb.model.UserProfile;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineException;
import com.rtb.pipeline.PipelineStage;
import com.rtb.repository.UserSegmentRepository;

import java.util.Set;

/** Fetches user segments from the repository and attaches to context. */
public final class UserEnrichmentStage implements PipelineStage {

    private final UserSegmentRepository repository;

    public UserEnrichmentStage(UserSegmentRepository repository) {
        this.repository = repository;
    }

    @Override
    public void process(BidContext ctx) {
        String userId = ctx.getRequest().userId();
        try {
            Set<String> segments = repository.getSegments(userId);
            ctx.setUserProfile(new UserProfile(userId, segments));
        } catch (Exception e) {
            throw new PipelineException("Redis lookup failed for user: " + userId, e);
        }
    }

    @Override
    public String name() {
        return "UserEnrichment";
    }
}
