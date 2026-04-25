package com.rtb.pipeline.stages;

import com.rtb.model.AdCandidate;
import com.rtb.pipeline.BidContext;
import com.rtb.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    // Pre-built comparator: highest value_per_click first.
    private static final Comparator<AdCandidate> BY_VALUE_DESC =
            Comparator.comparingDouble((AdCandidate c) -> c.getCampaign().valuePerClick()).reversed();

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
        if (candidates.size() <= maxCandidates) {
            return; // already within budget — no sort, no allocation
        }

        // Copy into a mutable ArrayList — upstream stages may return immutable lists
        // (e.g., Collectors.toUnmodifiableList()), in which case sort() would throw.
        // O(N log N) sort, then take top maxCandidates. The copy is unavoidable here
        // for correctness, and List.copyOf detaches the result from the working buffer.
        ArrayList<AdCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(BY_VALUE_DESC);
        ctx.setCandidates(List.copyOf(sorted.subList(0, maxCandidates)));
    }

    @Override
    public String name() {
        return "CandidateLimit";
    }
}
