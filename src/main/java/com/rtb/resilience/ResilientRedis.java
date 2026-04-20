package com.rtb.resilience;

import com.rtb.frequency.FrequencyCapper;
import com.rtb.repository.UserSegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;

/**
 * Wraps Redis-backed components (UserSegmentRepository + FrequencyCapper) with circuit breaker.
 *
 * When Redis is down:
 *   - getSegments() → returns empty set (user gets no targeting, not a crash)
 *   - isAllowed() → returns true (allow bid, don't block on freq check)
 *   - recordImpression() → silently dropped (freq counts under-reported for outage window)
 *
 * Graceful degradation: the bidder keeps serving ads with reduced precision
 * rather than failing every request because Redis is slow.
 */
public final class ResilientRedis implements UserSegmentRepository, FrequencyCapper {

    private static final Logger logger = LoggerFactory.getLogger(ResilientRedis.class);

    private final UserSegmentRepository segmentRepo;
    private final FrequencyCapper frequencyCapper;
    private final CircuitBreaker circuitBreaker;

    public ResilientRedis(UserSegmentRepository segmentRepo, FrequencyCapper frequencyCapper,
                          int failureThreshold, long cooldownMs) {
        this.segmentRepo = segmentRepo;
        this.frequencyCapper = frequencyCapper;
        this.circuitBreaker = new CircuitBreaker("redis", failureThreshold, cooldownMs);
    }

    @Override
    public Set<String> getSegments(String userId) {
        return circuitBreaker.execute(
                () -> segmentRepo.getSegments(userId),
                () -> {
                    logger.debug("Redis circuit open — returning empty segments for user {}", userId);
                    return Collections.emptySet();
                }
        );
    }

    @Override
    public boolean isAllowed(String userId, String campaignId, int maxImpressions) {
        return circuitBreaker.execute(
                () -> frequencyCapper.isAllowed(userId, campaignId, maxImpressions),
                () -> true  // allow bid when freq check is unavailable
        );
    }

    @Override
    public void recordImpression(String userId, String campaignId) {
        circuitBreaker.execute(
                () -> { frequencyCapper.recordImpression(userId, campaignId); },
                () -> { logger.debug("Redis circuit open — skipping freq recording for {}", userId); }
        );
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
