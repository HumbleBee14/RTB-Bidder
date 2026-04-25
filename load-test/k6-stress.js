import http from 'k6/http';
import { sendBidAndRecord } from './helpers.js';

// Constant-arrival-rate stress at a parameterised RPS. Unlike the ramp test —
// whose percentiles are diluted by low-load stages — this script holds one
// target rate steady so every percentile reflects what actually happens at
// that exact load.
//
// Run via:
//   STRESS_RATE=5000  k6 run load-test/k6-stress.js
//   STRESS_RATE=10000 k6 run load-test/k6-stress.js
//   STRESS_RATE=25000 k6 run load-test/k6-stress.js
// Or via Makefile shortcuts: make load-test-stress-5k / 10k / 25k / 50k.
//
// Each scenario runs in two phases:
//   1. WARMUP (30s)  — JIT promotes hot methods to C2; Caffeine cache fills;
//                      CPU L1/branch-predictor + Lettuce connection warm up.
//                      These samples are tagged phase=warmup and excluded
//                      from threshold evaluation.
//   2. MEASURE (3m)  — steady-state window. ALL threshold assertions apply
//                      to this phase only via the {phase:measure} filter.
//
// k6 client-side ceiling notice: at 25K+ RPS, k6 itself starts to consume
// significant CPU on the host that runs it. If k6's process CPU goes above
// ~80% during a run, you have likely found the test-rig limit, not the
// bidder limit. Cross-check against Grafana's server-side
// pipeline_stage_latency_seconds_bucket histograms — those measure the
// server's own observed latency and don't suffer the client-side bottleneck.
const RATE     = parseInt(__ENV.STRESS_RATE || '5000');
const DURATION = __ENV.STRESS_DURATION      || '3m';
const WARMUP   = __ENV.STRESS_WARMUP        || '30s';

// VU sizing — needs enough headroom that k6 itself is never the constraint,
// including during the warmup→measure handoff when response times can briefly
// spike to 20–30 ms before settling.
//
// preAllocatedVUs is what k6 reserves up-front; maxVUs is the ceiling it can
// grow to if the server slows. Sized assuming worst-case 25 ms response time
// (covers warmup transients + any tail jitter at the per-rate knee) with 5×
// safety factor.
//
// Empirical: at 5K RPS with the original (RATE × 0.005 × 3) formula, k6 hit
// the 100-VU ceiling at 30s and emitted "Insufficient VUs" — under-fired for
// 1–2s. The bumped formula prevents that.
const baseVUs = Math.max(200, Math.ceil(RATE * 0.025 * 5));
const maxVUs  = Math.max(baseVUs * 2, Math.ceil(RATE * 0.05));

export const options = {
    discardResponseBodies: true,
    scenarios: {
        warmup: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: WARMUP,
            preAllocatedVUs: baseVUs,
            maxVUs: maxVUs,
            exec: 'fire',
            tags: { phase: 'warmup' },
        },
        measure: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: baseVUs,
            maxVUs: maxVUs,
            exec: 'fire',
            startTime: WARMUP,
            tags: { phase: 'measure' },
        },
    },

    // Tightened thresholds applied ONLY to the measure phase (warmup excluded
    // by tag filter). abortOnFail: true on the critical asserts so a serious
    // regression aborts in seconds rather than running the full 3-minute test.
    thresholds: {
        'http_req_duration{phase:measure}': [
            { threshold: 'p(50)<20',    abortOnFail: true },   // realistic floor: 280-key MGET takes ~13ms
            { threshold: 'p(95)<45',    abortOnFail: true },
            { threshold: 'p(99)<50',    abortOnFail: true },   // SLA boundary
            { threshold: 'p(99.9)<100', abortOnFail: false },
        ],
        'error_rate{phase:measure}': [{ threshold: 'rate<0.01', abortOnFail: true }],
        'bid_rate{phase:measure}':   ['rate>0.80'],
    },
};

export function fire() {
    sendBidAndRecord(http);
}
