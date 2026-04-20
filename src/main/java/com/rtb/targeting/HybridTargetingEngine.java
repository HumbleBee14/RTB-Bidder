package com.rtb.targeting;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Routes to segment or embedding targeting based on request context.
 *
 * If context_text is present → use embedding targeting (AI-chat scenario).
 * If no context_text → use segment targeting (classical ad-tech).
 * If embedding targeting returns no matches → fall back to segment targeting.
 */
public final class HybridTargetingEngine implements TargetingEngine {

    private static final Logger logger = LoggerFactory.getLogger(HybridTargetingEngine.class);

    private final SegmentTargetingEngine segmentEngine;
    private final EmbeddingTargetingEngine embeddingEngine;

    public HybridTargetingEngine(SegmentTargetingEngine segmentEngine,
                                  EmbeddingTargetingEngine embeddingEngine) {
        this.segmentEngine = segmentEngine;
        this.embeddingEngine = embeddingEngine;
        logger.info("Hybrid targeting: segment + embedding (with fallback)");
    }

    @Override
    public List<AdCandidate> match(List<Campaign> campaigns, UserProfile user, AdContext context) {
        if (context.contextText() != null && !context.contextText().isBlank()) {
            List<AdCandidate> embeddingMatches = embeddingEngine.match(campaigns, user, context);
            if (!embeddingMatches.isEmpty()) {
                logger.debug("Embedding targeting matched {} candidates", embeddingMatches.size());
                return embeddingMatches;
            }
            logger.debug("Embedding targeting found no matches, falling back to segment targeting");
        }

        return segmentEngine.match(campaigns, user, context);
    }
}
