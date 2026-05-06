package com.rtb.frequency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process L1 cache for frequency-cap counters, sitting in front of a
 * Redis-backed {@link FrequencyCapper}. Mirrors the design of the Rust sibling
 * project's `InProcessFrequencyCapper`. See
 * `docs/IN-PROCESS-FREQUENCY-CACHE.md` in the Rust repo for the full contract,
 * consistency model, and the cold-miss-cache fix that made it actually work.
 *
 * <h2>How it works</h2>
 *
 * The cache is keyed at {@code (userId)} with the value being a
 * {@code Map<campaignId, AtomicLong>} of impression counters. On read:
 * <ol>
 *   <li>Look up the user's counter map in Caffeine (sub-µs).</li>
 *   <li>For each candidate campaign, compare the counter to the per-campaign cap.</li>
 *   <li>If a candidate isn't in the user's map (cold miss for that pair), defer
 *       to the underlying Redis-backed capper for ONLY the missing candidates.</li>
 * </ol>
 *
 * On {@link #recordImpression(String, String)} we increment the in-process
 * counter immediately AND fire the underlying capper's write-back to Redis.
 * Reads see the new count instantly; cross-process consistency is bounded by
 * Redis's own latency.
 *
 * <h2>Step #1 caveat — cold-miss-write not yet implemented</h2>
 *
 * The Rust analysis (see Rust repo's `docs/PERFORMANCE-DEBUGGING.md`) found
 * that without the "cold-miss-write" optimization (Option A in our notes), the
 * cache hit rate at the (user, campaign) level stays near zero — wide candidate
 * fan-out means most candidates per request are first-time pairs for that user.
 *
 * This impl is INTENTIONALLY missing that optimization for now. We're shipping
 * step #1 (Caffeine cache + write-through on impressions) first so the
 * before/after measurement is clean. Step #2 (insert Redis-returned counts
 * into Caffeine after a cold miss) is a separate change.
 *
 * <h2>Multi-instance correctness</h2>
 *
 * Like the Rust sibling, this is single-instance-correct only. Two pods with
 * independent in-process caches can both approve the same (user, campaign)
 * impression up to the cap, exceeding it across the fleet. Use only when:
 * sticky-by-user routing OR a single bidder instance OR an SLA that tolerates
 * a small over-cap.
 */
public final class InProcessFrequencyCapper implements FrequencyCapper, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(InProcessFrequencyCapper.class);

    /**
     * One impression counter for a single (user, campaign) pair. The cap is
     * a *sliding window* in Redis (TTL=1h on the key, refreshed on first
     * INCR). To match that semantically without per-key wall-clock plumbing,
     * each counter carries its own absolute expiry timestamp:
     *
     *   first increment       → value=1, expiresAtMs = now + window
     *   subsequent increments → value++ (no change to expiry)
     *   read after expiresAtMs → treat as 0 (window has rolled), reset on next bump
     *
     * Without this, the in-process counter monotonically grows — every user
     * exhausts their cap during warmup and stays exhausted forever.
     */
    private static final class WindowedCounter {
        final LongAdder value = new LongAdder();
        volatile long expiresAtMs;

        /** New counter, no expiry yet. The first {@link #bump} sets expiresAtMs. */
        WindowedCounter() {
            this.expiresAtMs = 0L;
        }

        long currentCount(long nowMs) {
            return nowMs >= expiresAtMs ? 0 : value.sum();
        }

        /** Increment, rolling the window if expired or first-use. */
        synchronized void bump(long nowMs, long windowMs) {
            if (nowMs >= expiresAtMs) {
                value.reset();
                expiresAtMs = nowMs + windowMs;
            }
            value.increment();
        }
    }

    private final FrequencyCapper fallback;
    private final Cache<String, ConcurrentHashMap<String, WindowedCounter>> cache;
    private final long windowMs;
    private final Counter coldMissTotal;
    private final Counter cacheHitTotal;
    private final Counter recordedImpressions;

    public InProcessFrequencyCapper(FrequencyCapper fallback,
                                    long maxUsers,
                                    Duration ttl,
                                    MeterRegistry registry) {
        this.fallback = fallback;
        // The cache holds the per-user map. Outer expiry is for *eviction
        // under memory pressure*, not cap-window enforcement — that's done
        // per-counter via WindowedCounter.expiresAtMs.
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxUsers)
                .expireAfterAccess(ttl)
                .build();
        // Cap window matches the Redis sliding window. Hardcoded to 1h to
        // match RedisFrequencyCapper.WINDOW_SECONDS — keep these aligned.
        this.windowMs = Duration.ofHours(1).toMillis();
        this.coldMissTotal = Counter.builder("freq_cap_in_process_cold_miss_total")
                .description("Per-(user, campaign) lookups that fell through to Redis")
                .register(registry);
        this.cacheHitTotal = Counter.builder("freq_cap_in_process_cache_hit_total")
                .description("Per-(user, campaign) lookups served from the in-process cache")
                .register(registry);
        this.recordedImpressions = Counter.builder("freq_cap_in_process_record_impression_total")
                .description("Impressions recorded into both the in-process cache and Redis")
                .register(registry);

        logger.info("InProcessFrequencyCapper enabled (maxUsers={}, ttl={}) — single-instance " +
                        "consistency only; ensure sticky-by-user routing or single-pod deploy",
                maxUsers, ttl);
    }

    @Override
    public boolean isAllowed(String userId, String campaignId, int maxImpressions) {
        long now = System.currentTimeMillis();
        ConcurrentHashMap<String, WindowedCounter> userMap = cache.getIfPresent(userId);
        if (userMap != null) {
            WindowedCounter counter = userMap.get(campaignId);
            if (counter != null) {
                cacheHitTotal.increment();
                return counter.currentCount(now) < maxImpressions;
            }
        }
        // Cold miss → defer to Redis. Step #2 (cold-miss-write) intentionally
        // omitted — we want to measure the miss rate first.
        coldMissTotal.increment();
        return fallback.isAllowed(userId, campaignId, maxImpressions);
    }

    @Override
    public Set<String> allowedCampaignIds(String userId, Map<String, Integer> campaignMaxImpressions) {
        if (campaignMaxImpressions.isEmpty()) {
            return Set.of();
        }

        long now = System.currentTimeMillis();
        Set<String> allowed = new HashSet<>();
        Map<String, Integer> coldMisses = null;       // built lazily
        ConcurrentHashMap<String, WindowedCounter> userMap = cache.getIfPresent(userId);

        for (Map.Entry<String, Integer> e : campaignMaxImpressions.entrySet()) {
            String campaignId = e.getKey();
            int cap = e.getValue();

            WindowedCounter counter = (userMap != null) ? userMap.get(campaignId) : null;
            if (counter != null) {
                cacheHitTotal.increment();
                if (counter.currentCount(now) < cap) {
                    allowed.add(campaignId);
                }
            } else {
                if (coldMisses == null) coldMisses = new HashMap<>();
                coldMisses.put(campaignId, cap);
            }
        }

        if (coldMisses != null && !coldMisses.isEmpty()) {
            coldMissTotal.increment(coldMisses.size());
            Set<String> redisAllowed = fallback.allowedCampaignIds(userId, coldMisses);
            allowed.addAll(redisAllowed);
            // Step #2 (TODO): insert (campaignId → Redis count) into the
            // Caffeine entry here so the next bid sees a hit.
        }

        return allowed;
    }

    @Override
    public void recordImpression(String userId, String campaignId) {
        long now = System.currentTimeMillis();
        // 1) In-process: bump the counter immediately so subsequent reads
        //    see it. The WindowedCounter handles cap-window TTL itself —
        //    if `now > expiresAtMs` the bump resets the counter to 1 with
        //    a fresh expiry, mirroring Redis's INCR-with-EXPIRE behaviour.
        ConcurrentHashMap<String, WindowedCounter> userMap =
                cache.get(userId, k -> new ConcurrentHashMap<>());
        WindowedCounter counter = userMap.computeIfAbsent(
                campaignId, k -> new WindowedCounter());
        counter.bump(now, windowMs);
        recordedImpressions.increment();

        // 2) Redis: forward to the underlying capper for cross-process durability.
        fallback.recordImpression(userId, campaignId);
    }

    /** For test introspection only. */
    public long approximateCacheSize() {
        return cache.estimatedSize();
    }

    @Override
    public void close() {
        if (fallback instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.warn("Failed to close fallback capper: {}", e.getMessage());
            }
        }
        cache.invalidateAll();
    }
}
