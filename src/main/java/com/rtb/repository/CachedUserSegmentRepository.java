package com.rtb.repository;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineStatsCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * In-process LRU cache for user segments — wraps any {@link UserSegmentRepository}.
 *
 * WHY this exists:
 *   The hot path does one synchronous Redis SMEMBERS per bid request. At 1ms round-trip,
 *   a single event-loop thread saturates at ~150 RPS. Moving the pipeline to worker
 *   threads (Slice 2) raises the ceiling, but Redis is still the bottleneck inside each
 *   worker — 1ms × 72 workers = 72 RPS ceiling per worker-slot.
 *
 *   Caching removes Redis from the hot path entirely for known users. With 1M users and
 *   uniform distribution, a 500K-entry cache has ~50% cold-start miss rate; after warmup
 *   the hit rate climbs to 99%+. Segment data changes on the order of minutes-to-hours
 *   (segment membership is updated by batch jobs, not in real-time), so a 60s TTL is
 *   safe and gives fresh segments after any bulk update.
 *
 * Caffeine W-TinyLFU vs plain LRU:
 *   W-TinyLFU keeps frequently-accessed entries even when there's a brief scan of cold
 *   entries (e.g., the 1M cold-start sweep). Plain LRU would evict hot entries during
 *   that scan. For RTB traffic (some users bid more often than others) this matters.
 *
 * Metrics:
 *   Caffeine stats are published under cache_user_segments_* via CaffeineStatsCounter.
 *   Watch hit_rate in Grafana: should approach 1.0 after ~5 minutes of load.
 */
public final class CachedUserSegmentRepository implements UserSegmentRepository {

    private static final Logger logger = LoggerFactory.getLogger(CachedUserSegmentRepository.class);

    private final UserSegmentRepository delegate;
    private final Cache<String, Set<String>> cache;

    public CachedUserSegmentRepository(UserSegmentRepository delegate,
                                        int maxSize,
                                        long ttlSeconds,
                                        MeterRegistry registry) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .recordStats(() -> new CaffeineStatsCounter(registry, "cache_user_segments"))
                .build();

        logger.info("User segment cache: maxSize={}, ttl={}s", maxSize, ttlSeconds);
    }

    @Override
    public Set<String> getSegments(String userId) {
        return cache.get(userId, delegate::getSegments);
    }
}
