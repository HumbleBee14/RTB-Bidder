# Multi-Slot Bidding — Architecture Analysis

## What is multi-slot?

A single bid request from the exchange contains multiple ad slots (impressions). Example: a news app page has a leaderboard banner at top, a rectangle ad mid-feed, and a sidebar skyscraper.

```
Exchange sends ONE request:
  bid_request {
    imp: [
      { id: "slot-top",     banner: { format: [{w:728, h:90}]  }, bid_floor: 0.80 },
      { id: "slot-mid",     banner: { format: [{w:300, h:250}] }, bid_floor: 0.50 },
      { id: "slot-sidebar", banner: { format: [{w:160, h:600}] }, bid_floor: 0.30 }
    ]
  }

DSP responds with bids for slots it wants:
  bid_response {
    seatbid: [{
      bid: [
        { impid: "slot-top",  price: 0.90, adid: "nike-banner",   w: 728, h: 90  },
        { impid: "slot-mid",  price: 0.60, adid: "techcorp-rect", w: 300, h: 250 }
        // slot-sidebar: no bid (nothing matched or below floor)
      ]
    }]
  }
```

The DSP doesn't have to bid on all slots. It bids only where it has a viable candidate.

## What production DSPs actually do

### Processing model: fan-out per slot, not sequential

Production DSPs do NOT process slots one after another. That would multiply latency by slot count (3 slots × 50ms = 150ms → SLA violation).

Instead, they use **fan-out-then-merge**:
1. User enrichment, segment lookup — done ONCE (shared across all slots)
2. Per-slot processing (candidate filtering by size, scoring, ranking) — run concurrently
3. Merge results, deduplicate, build response

```
  Request arrives
       │
       ▼
  Shared work (once):
    User lookup → Redis SMEMBERS → UserProfile
    Campaign load → CachedCampaignRepository
       │
       ├─── Slot 1 (728x90) ──→ filter by size → score → rank → winner
       ├─── Slot 2 (300x250) ─→ filter by size → score → rank → winner
       └─── Slot 3 (160x600) ─→ filter by size → score → rank → winner
                                                                 │
                                                                 ▼
                                                          Dedup + merge
                                                                 │
                                                                 ▼
                                                          BidResponse
```

### Real-world numbers

| Company | Approach | Latency | QPS |
|---------|----------|---------|-----|
| Adobe Advertising | Per-impression internal auction, bitmap indexes | p95 < 60ms | 5M ops/sec |
| Moloco | Staged pipeline, pre-generated feature vectors | < 100ms | 5M+ QPS |
| The Trade Desk | Per-impression with GPID deduplication | < 100ms | Millions |

### Creative size matching

Each impression in OpenRTB declares allowed sizes via `banner.format[]` (list of `{w, h}` pairs). The DSP filters candidate creatives against these sizes BEFORE scoring. A 300x250 creative cannot win a 728x90 slot — the exchange would reject it.

In production, this is a precomputed index lookup (O(1) bitmap), not a per-request loop.

### Campaign deduplication across slots

The same campaign should NOT win multiple slots in the same request (user sees Nike three times = bad UX). OpenRTB provides `SeatBid.group` for all-or-nothing bidding but NO built-in per-campaign dedup.

DSPs handle this internally:
- Track which campaigns have been selected for previous slots in this request
- Skip already-selected campaigns when ranking the next slot
- The ranking loop processes slots in order of descending value (bid the best slot first)

### "Best slot" bidding

Some DSPs intentionally bid on only one slot per request — the one with the highest expected value. This is simpler, lower latency, and valid. The exchange fills the other slots from other bidders.

This is what our current implementation does. It's not wrong — it's a valid production strategy. But it leaves revenue on the table.

## Per-stage breakdown — what's shared vs per-slot

| Stage | Shared or Per-slot? | Why | I/O? |
|-------|-------------------|-----|------|
| **RequestValidation** | Shared (once) | Validates request structure — same request regardless of slots | None |
| **UserEnrichment** | Shared (once) | Same user viewing the page — one Redis SMEMBERS call | Redis |
| **CandidateRetrieval** | Shared (once) | Targeting is user→campaign matching — user segments don't change per slot | None (in-memory) |
| **FrequencyCap** | Shared (once) | "User saw Nike 5 times" is per-user per-campaign, NOT per-slot — capped for ALL slots equally | Redis |
| **Scoring** | Shared (once) | Score = segment overlap × price × pacing — all user-dependent, not slot-dependent. Nike's relevance to user_00042 is 0.25 whether it's a 728x90 or 300x250 slot | None (arithmetic) |
| **Creative size filter** | **Per-slot** | 300x250 creative can't go in 728x90 slot — must check per slot | None (set lookup) |
| **Bid floor filter** | **Per-slot** | Slot-top floor $0.80 ≠ slot-sidebar floor $0.30 — must check per slot | None (comparison) |
| **Winner selection** | **Per-slot** | Each slot picks its own highest-scoring candidate | None (max-scan) |
| **Campaign dedup** | **Per-slot** | If Nike won slot-top, skip Nike for slot-mid and slot-sidebar | None (set lookup) |
| **Response build** | **Per-slot** | One SlotBid per slot with winner's campaign data | None |

**Key insight:** all the per-slot work is boolean checks and comparisons — zero I/O. The expensive work (Redis calls) runs once regardless of slot count.

## Why per-slot work is pure in-memory (no extra I/O)

The question: if we have 3 slots, don't we need 3x the Redis calls, 3x the scoring, 3x everything?

No. Here's why — look at what each stage actually depends on:

```
Stage                  | Depends on...         | Changes per slot?
───────────────────────┼───────────────────────┼──────────────────
RequestValidation      | request structure     | NO  (same request)
UserEnrichment         | user_id               | NO  (same user)
CandidateRetrieval     | user segments         | NO  (same user → same matches)
FrequencyCap           | user + campaign       | NO  (user saw Nike 5x → capped for ALL slots)
Scoring                | user + campaign       | NO  (relevance score is user-dependent, not slot-dependent)
───────────────────────┼───────────────────────┼──────────────────
Creative size filter   | slot's allowed sizes  | YES (728x90 slot ≠ 300x250 slot)
Bid floor filter       | slot's bid_floor      | YES ($0.80 floor ≠ $0.30 floor)
Winner selection       | slot's eligible pool  | YES (each slot picks its own winner)
```

The EXPENSIVE work (Redis calls for segments, Redis calls for frequency, campaign matching) runs ONCE.
The PER-SLOT work is two boolean checks (size match + floor match) and a max-scan — pure CPU, microseconds.

Example with real numbers:
```
Shared work (once):
  UserEnrichment:     3.0ms  (Redis SMEMBERS)
  FrequencyCap:      12.0ms  (Redis GET × N candidates)
  CandidateRetrieval: 0.05ms (in-memory segment matching)
  Scoring:            0.07ms (arithmetic per candidate)
  ─────────────────────────
  Subtotal:          15.12ms

Per-slot work (3 slots × ~0.1ms each):
  Size filter:        0.01ms × 3 = 0.03ms
  Floor filter:       0.01ms × 3 = 0.03ms
  Rank + dedup:       0.02ms × 3 = 0.06ms
  ─────────────────────────
  Subtotal:           0.12ms

Total: 15.12ms + 0.12ms = 15.24ms  (NOT 15ms × 3 = 45ms)
```

The key insight: a campaign's SCORE (how relevant Nike is to user_00042) is the same
regardless of which slot it's being considered for. What differs per slot is:
- Does Nike have a creative that fits this slot's size? (boolean check)
- Can Nike afford this slot's bid floor? (number comparison)
- Has Nike already won another slot in this request? (set lookup)

## Our design choice

We implement **fan-out per slot** with deduplication:

1. **Shared stages** (run once): RequestValidation, UserEnrichment, CandidateRetrieval, FrequencyCap, Scoring
2. **Per-slot logic** (in RankingStage): creative size filter, bid floor filter, campaign dedup, pick highest-scoring winner
3. **Response**: list of SlotBid (one per slot that had a winner), built by ResponseBuildStage from slotWinners map

Performance impact: the shared stages (which include Redis calls — the expensive part) run once regardless of slot count. Per-slot scoring is O(candidates × slots) pure arithmetic — microseconds. No latency multiplication.

## References

- [OpenRTB 2.6 Spec](https://github.com/InteractiveAdvertisingBureau/openrtb2.x/blob/main/2.6.md)
- [Moloco DSP Infrastructure](https://www.moloco.com/r-d-blog/challenges-in-building-a-scalable-demand-side-platform-dsp-service)
- [Adobe: Behind the Curtain of a High-Performance Bidder](https://experienceleaguecommunities.adobe.com/t5/adobe-advertising-cloud-blogs/behind-the-curtain-of-a-high-performance-bidder-service/ba-p/765771)
- [The Trade Desk Bid Duplication](https://www.adexchanger.com/platforms/the-trade-desk-suppresses-bid-duplication-amid-covid-19-traffic-surge/)
