# v5 Experiment Plan — pushing past the ~10K RPS comfort zone

v4 left us at: 5K and 10K RPS sustained with full SLA compliance, ~25K hits the box's CPU ceiling. v5 is about exploring what's *actually* limiting us at 25K and which changes recover the most headroom for the least cost.

## The framing question

At 25K RPS, on this MacBook, **what's actually consuming CPU?** Three distinct possibilities:

1. **The bidder JVM** — scoring, sorting, segment matching, JSON, allocation
2. **Co-located infra** (Docker containers eating cores: Postgres, Kafka, ClickHouse, Prometheus, Grafana — only Redis is on the bidder hot path)
3. **k6 itself** — the load generator at 25K RPS is non-trivial CPU

Until we split (1) from (2)+(3), every "optimisation" we try is fishing in the dark. **Step 1 of v5 is measurement, not coding.**

---

## Phase A — measurement first (cheap, do these before anything else)

### A.1 Run with `top -o cpu` open during a 25K probe

Just to see the breakdown. Don't change anything else. Look for:
- `java` (bidder) — what % of total CPU?
- `k6` — what % of total CPU?
- `redis-server` — what % of total CPU?
- `com.docker.virtualization` and friends — Docker overhead?

If `java` is at 80%+ alone, the bidder is the wall. If it's split with k6 (each ~40-50%), the test rig is half the constraint.

**Cost:** 5 minutes. **Output:** clarity on what to attack.

### A.2 Run with `events.type=noop` (skip Kafka publishing entirely)

Set in `application.properties` or `EVENTS_TYPE=noop` env var. This eliminates the Kafka producer thread (which JFR showed as one of the hottest threads in the v4 10K profile — ~536 samples).

Re-run 10K and 15K soaks and compare to v4 numbers.

If 15K passes with `events.type=noop` but fails with `events.type=kafka`, we've localised significant CPU to event publishing.

**Cost:** env var change + 2 soaks (~10 minutes). **Output:** quantifies Kafka producer cost.

### A.3 Stop the unused Docker containers

We probably don't need ClickHouse, Prometheus, Grafana running during raw benchmarking. Stop them with `docker-compose stop clickhouse prometheus grafana`. Keep Redis (hot path), Postgres (campaigns), Kafka (only if testing with `events.type=kafka`).

Re-run 10K and 15K soaks.

**Cost:** one command + 2 soaks. **Output:** quantifies idle-infra overhead.

### A.4 Run k6 from a separate machine

The hardest of the cheap experiments. Either another laptop on the same network, or a small VPS. k6's outbound network overhead at 25K is non-trivial.

**Cost:** depends on what's available. **Output:** definitive bidder ceiling, decoupled from load-gen.

---

## Phase B — code-level experiments (after we know where the cycles go)

### B.1 Tighten the candidate limit

`pipeline.candidates.max=16` (currently 32). Halves scoring + sorting + freq-cap MGET work per request. Top-16 by `value_per_click` still contains the eventual winner ~95% of the time on this catalog (vs ~99% at 32) — small bid-quality cost for big CPU saving.

Try `=8` as well to find where bid quality starts to actually degrade.

**Cost:** properties tweak. **Output:** linear scaling curve.

### B.2 Increase Lettuce connection count

`redis.pool.size=8` or `=16` (currently 4). More decoder threads if Redis decode is still a meaningful contributor. JFR will tell us if this helps or just spreads the same total work across more idle threads.

**Cost:** properties tweak. **Output:** confirms whether Lettuce is the wall.

### B.3 Vectorised batch scoring

Replace the per-candidate `FeatureWeightedScorer.computeRelevance` (HashMap-based) with an array-indexed scorer that scores N candidates at once. JFR consistently shows `HashMap.containsKey` and `HashMap.getNode` as top hot methods in scoring — those become array indexing if features map to fixed positions.

**Cost:** ~half day. **Risk:** medium. **Expected gain:** 2-3× scoring throughput on a single core.

### B.4 Bitmap segment matching

Replace `SegmentTargetingEngine.hasOverlap` (Set intersection over String segment names) with a bitmap-AND over `long` (or `BitSet`) where each bit position is a fixed segment ID. Same correctness, nanoseconds vs microseconds per overlap check.

Requires:
- Pre-assigning a stable integer ID to each segment (loaded at startup)
- Pre-computing each campaign's segment bitmap once at load
- Per-request: convert user's segments to a bitmap, AND with each campaign's bitmap, non-zero = match

**Cost:** ~half day. **Risk:** medium (segment ID stability across restarts, etc). **Expected gain:** 5-10× faster targeting.

### B.5 Compact user segment storage (the "GET-blob" idea)

Replace Redis Set + SMEMBERS with one of:
- A single key holding a CSV/packed string, fetched with GET
- A bitmap stored as a fixed-length byte string, fetched with GETRANGE

GET is simpler than SMEMBERS to decode (one bulk string vs multi-bulk). Bigger win: combined with B.4's bitmap, we get bitmap → byte[] → fetch in one shot.

Requires re-seeding all 1M users.

**Cost:** ~1 day (seeding script + repository rewrite + targeting consumer). **Risk:** higher. **Expected gain:** 10-20% Redis CPU on the bidder side, unclear app-side gain since segment matching dominates.

**Defer until measurement (Phase A) shows Redis is actually the wall.** v4 JFR showed it wasn't.

---

## Phase C — comparative reference (read RTB4FREE)

The user wants to clone RTB4FREE for comparison. Two questions worth answering by reading their code:

### C.1 What's their per-request hot path?

- Do they segment-match against all campaigns, or pre-index?
- Do they score with ML, with weights, or with constants?
- How big is the catalog they tested with?
- What's their Redis schema (Set vs string vs bitmap)?

### C.2 What's their test methodology?

- How did they measure 25K RPS — what hardware, what catalog, what SLA?
- Did they run load-gen on a separate machine?
- Were thresholds strict (p99<50ms) or just "responds"?
- Did they pre-warm caches?

If their 25K was on dedicated bare-metal with 100 campaigns and load-gen elsewhere, that's not directly comparable to ours. If it was a similar setup, their architecture has lessons we should steal.

### C.3 Direct head-to-head benchmark

If RTB4FREE has a Docker setup, run it on this same MacBook. Then compare on identical hardware:
- Their bidder + their generator at 25K — does it actually deliver?
- Replace their bidder with ours, leave their generator. Or vice versa.

That decouples "their architecture is faster" from "their test was easier."

---

## Suggested execution order

If goal is **honest comparison with RTB4FREE**, then C.1 and C.3 first — read their code, run their stack on this hardware, see what their numbers actually are on equal footing.

If goal is **maximise our own RPS quickly**, then A.1 → A.2 → A.3 first (free experiments to localise the bottleneck), then B.1 + B.2 (cheap config changes), then B.3 or B.4 based on what JFR says.

If goal is **understand the system deeply**, do all of Phase A as a measurement campaign, write up findings, then pick Phase B targets with data.

---

## What NOT to do (anti-patterns)

- **Don't increase RPS via timeout relaxation.** Anything that loosens `pipeline.sla.maxLatencyMs` or k6 thresholds isn't an improvement, it's moving goalposts.
- **Don't pre-warm caches before benchmarking.** Real production may have warm caches, but our cold-start numbers are the honest baseline. Warm-cache numbers belong in a separate "best case" report.
- **Don't reduce the user pool below 1M or campaign count below 1000.** That makes our numbers incomparable to v4.
- **Don't disable freq-cap or budget-pacing logic.** Those are real correctness obligations, not nice-to-haves.
- **Don't drop bid responses to fit the SLA.** Different from `ImpressionRecorder` queue drops — those are post-response writes; dropping the actual bid response would be cheating.

---

## When v5 is "done"

We have one of these outcomes documented:

1. **A clean 15K or 20K RPS soak passes** with the same SLA we used in v4, on the same single-machine setup. Document which Phase A or B change(s) got us there.
2. **We've definitively shown 25K is unreachable on this hardware** with measurement, not just a CPU graph. RTB4FREE comparison either confirms this or shows what they did differently.
3. **We've gotten 25K to pass by moving k6/infra off the box** but the bidder process itself was always capable. That's still useful information — it tells production deployment what the bidder actually needs.

Each outcome justifies a v5 results doc.
