package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Truncates the candidate list to the top N by a cheap heuristic, BEFORE expensive scoring.
 *
 * Why before scoring (not after):
 *   Scoring — especially ML-based — is the most expensive stage in the pipeline.
 *   With 1000 campaigns and ~278 surviving targeting + freq-cap, running an ONNX
 *   model 278 times per bid burns CPU we don't need to spend. A pre-rank by
 *   value_per_click captures most of the upside (high-value campaigns are the ones
 *   we'd pick anyway) while cutting scoring work by ~80%.
 *
 * Heuristic — value_per_click:
 *   Each campaign's payout per click is a pure data signal: no model, no Redis,
 *   no allocation beyond the sort. Higher value_per_click = higher expected revenue
 *   per impression, so it's a reasonable proxy for "worth scoring properly."
 *
 * Configuration (pipeline.candidates.max):
 *   0   → unlimited (default; pass-through, no sort cost)
 *   25–50  → strict latency budgets (sub-10ms SLA)
 *   50–100 → typical mid-size DSPs
 *   100–200→ Google-Ads / Meta-scale (richer ML, more headroom)
 *
 * Setting this above your average candidate count is a no-op (we skip the sort
 * when candidates.size() <= max), so leaving the default of 0 is safe — the cost
 * appears only when truncation is actually needed.
 */
public final class CandidateLimitStage implements PipelineStage {

    private final int maxCandidates;
    // Min-heap comparator: SMALLEST value_per_click on top, ready for eviction
    // when a better candidate arrives. That's how a fixed-size top-K heap works.
    private static final Comparator<AdCandidate> BY_VALUE_ASC =
            Comparator.comparingDouble(c -> c.getCampaign().valuePerClick());

    /**
     * @param maxCandidates  cap on candidates passed to the next stage.
     *                       0 disables the limit entirely (pipeline pass-through).
     */
    public CandidateLimitStage(int maxCandidates) {
        if (maxCandidates < 0) {
            throw new IllegalArgumentException(
                    "pipeline.candidates.max must be >= 0, got: " + maxCandidates);
        }
        this.maxCandidates = maxCandidates;
    }

    @Override
    public void process(BidContext ctx) {
        if (maxCandidates == 0) {
            return; // unlimited — no-op fast path
        }

        List<AdCandidate> candidates = ctx.getCandidates();
        int n = candidates.size();
        if (n <= maxCandidates) {
            return; // already within budget — no work
        }

        // Top-K selection via a bounded min-heap. The heap holds the K best-so-far
        // candidates; the smallest one sits on top, ready to be evicted when a better
        // one arrives. Total cost: O(N log K) compares vs O(N log N) for a full sort.
        // Same output: top-K by value_per_click, regardless of order in the heap.
        PriorityQueue<AdCandidate> topK = new PriorityQueue<>(maxCandidates + 1, BY_VALUE_ASC);
        for (AdCandidate c : candidates) {
            if (topK.size() < maxCandidates) {
                topK.offer(c);
            } else if (BY_VALUE_ASC.compare(c, topK.peek()) > 0) {
                // c.value > heap-top.value → replace the smallest with c
                topK.poll();
                topK.offer(c);
            }
        }

        // Drain into a new ArrayList. The downstream pipeline doesn't depend on order
        // here — ScoringStage scores everything regardless, RankingStage sorts by score
        // afterward. So leaving the heap order is safe.
        ArrayList<AdCandidate> result = new ArrayList<>(maxCandidates);
        result.addAll(topK);
        ctx.setCandidates(result);
    }

    @Override
    public String name() {
        return "CandidateLimit";
    }
}
