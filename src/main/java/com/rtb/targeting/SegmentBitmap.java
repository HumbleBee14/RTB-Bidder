package com.rtb.targeting;

import com.rtb.model.Campaign;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encodes segment-name strings into 64-bit bitmaps for O(1) intersection checks.
 *
 * Why this exists:
 *   The hot path computed segment overlap with Set<String> intersection — per call
 *   that's N string-hash + N HashMap lookups (where N = segments per side, ~10).
 *   At 15K RPS × 1000 campaigns × 10 segments per request, JFR showed
 *   HashMap.containsKey + String.charAt as top hot methods, summing 1500+ samples.
 *
 *   Replacing the Set<String> with a long bitmap collapses the intersection to a
 *   single bitwise AND (overlap test) or AND + popcount (match-count). Same result,
 *   ~10-50× faster per call, no allocation.
 *
 * Encoding:
 *   - SegmentRegistry assigns each unique segment name a stable integer id (0..63)
 *     on first sight. The 50 known segments in seed-redis.py fit in 64 bits.
 *   - Each segment becomes one bit in a `long`.
 *   - User segments and campaign target segments both encode the same way.
 *
 * Caching:
 *   - Campaign target bitmaps are stable across the campaign's lifetime; cached by
 *     campaign id in a ConcurrentHashMap (computed lazily on first access).
 *   - User segment bitmaps are computed per-request from the Set<String> we just
 *     fetched from Redis/Caffeine. Costs N hash lookups once, then 1000+ AND ops
 *     downstream get them for free.
 *
 * Capacity:
 *   - Capped at 64 segments (Long.SIZE). Above that the registry throws — surfacing
 *     the limit explicitly rather than silently truncating. Adequate for the 50
 *     segment names we seed; production fleets with hundreds of segments would
 *     switch to BitSet (or a fixed-size long[]) at the same call sites.
 */
public final class SegmentBitmap {

    /** Maximum distinct segments the bitmap can hold. */
    public static final int MAX_SEGMENTS = Long.SIZE;

    private static final ConcurrentHashMap<String, Integer> ID_BY_NAME = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0);

    /** Per-campaign cached target bitmap, keyed by campaign id. */
    private static final ConcurrentHashMap<String, Long> CAMPAIGN_BITMAPS = new ConcurrentHashMap<>();

    private SegmentBitmap() {}

    /**
     * Get (or assign) the bit position for a segment name.
     * @throws IllegalStateException if more than MAX_SEGMENTS distinct names have been seen.
     */
    public static int idFor(String segment) {
        Integer existing = ID_BY_NAME.get(segment);
        if (existing != null) return existing;
        return ID_BY_NAME.computeIfAbsent(segment, s -> {
            int id = NEXT_ID.getAndIncrement();
            if (id >= MAX_SEGMENTS) {
                throw new IllegalStateException(
                        "SegmentBitmap exceeded " + MAX_SEGMENTS + " distinct segments. " +
                        "Switch to BitSet or expand the encoding. Newly-seen: " + segment);
            }
            return id;
        });
    }

    /** Encode a Set of segment names into a 64-bit bitmap. Empty set returns 0. */
    public static long encode(Set<String> segments) {
        long bits = 0L;
        for (String s : segments) {
            bits |= (1L << idFor(s));
        }
        return bits;
    }

    /** Lazy-cached target bitmap for a campaign. Computed once per campaign id. */
    public static long forCampaign(Campaign campaign) {
        Long cached = CAMPAIGN_BITMAPS.get(campaign.id());
        if (cached != null) return cached;
        long bits = encode(campaign.targetSegments());
        CAMPAIGN_BITMAPS.put(campaign.id(), bits);
        return bits;
    }

    /** Drop the campaign cache (call when the campaign list is reloaded). */
    public static void clearCampaignCache() {
        CAMPAIGN_BITMAPS.clear();
    }
}
