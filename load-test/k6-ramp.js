import http from 'k6/http';
import { sendBidAndRecord } from './helpers.js';

// Ramp test: gradually increase load to find the saturation knee — the RPS
// at which latency stops being flat and starts climbing sharply.
//
// Phase 17 (multi-thread + cache + MGET) extended the ramp top from 1000 RPS
// to 5000 RPS so the ramp on its own can find any knee that lives below 5K.
// For higher / per-rate measurements use k6-stress.js (constant-arrival-rate).
//
// preAllocatedVUs sized for 5000 RPS at ~5ms worst-case response = 75 VUs;
// rounded up to 200 for tail-latency overlap. maxVUs ceiling is 10000 in case
// the server slows past saturation — k6 grows VUs to absorb the queue.
export const options = {
    discardResponseBodies: true,
    scenarios: {
        ramp: {
            executor: 'ramping-arrival-rate',
            startRate: 10,
            timeUnit: '1s',
            preAllocatedVUs: 200,
            maxVUs: 10000,
            stages: [
                { target: 50,    duration: '20s' },   // warmup
                { target: 50,    duration: '30s' },   // hold — establish baseline
                { target: 200,   duration: '20s' },   // ramp 50 → 200
                { target: 200,   duration: '30s' },   // hold
                { target: 500,   duration: '20s' },   // ramp 200 → 500
                { target: 500,   duration: '30s' },   // hold
                { target: 1000,  duration: '20s' },   // ramp 500 → 1000
                { target: 1000,  duration: '30s' },   // hold (Run 2's previous top)
                { target: 2500,  duration: '30s' },   // ramp 1K → 2.5K — push into new territory
                { target: 2500,  duration: '45s' },   // hold — measure mid-stress percentiles
                { target: 5000,  duration: '30s' },   // ramp 2.5K → 5K — top stage
                { target: 5000,  duration: '60s' },   // hold — main measurement window
                { target: 0,     duration: '15s' },   // cooldown
            ],
        },
    },
    // Thresholds calibrated against Run 2 ramp results (p50≈1ms, p95≈2.4ms, max≈24ms,
    // error=0%, bid≈88%) with realistic margin for ramp transitions and queue spikes.
    // p99/p99.9/max have looser bounds than baseline because ramp transitions
    // briefly stress the queue; the percentiles below are still well within the 50ms SLA.
    thresholds: {
        http_req_duration: [
            { threshold: 'p(50)<10',  abortOnFail: true },
            { threshold: 'p(95)<25',  abortOnFail: true },
            { threshold: 'p(99)<50',  abortOnFail: true },   // SLA boundary
            { threshold: 'p(99.9)<100', abortOnFail: false },
        ],
        error_rate: [{ threshold: 'rate<0.005', abortOnFail: true }], // <0.5%
        bid_rate:   ['rate>0.80'],   // 80% floor — real catalog match under load
    },
};

export default function () {
    sendBidAndRecord(http);
}
