import http from 'k6/http';
import { sendBidAndRecord } from './helpers.js';

// Baseline: constant load for 2 minutes to establish stable latency numbers.
//
// Why constant-arrival-rate (not ramping-vus):
// - ramping-vus controls concurrency, not throughput. If your server slows down,
//   VUs accumulate in-flight and actual RPS drops — you won't notice backpressure.
// - constant-arrival-rate fires exactly N requests/sec regardless of server speed.
//   If the server can't keep up, k6 spawns more VUs. This models real exchange
//   traffic: the exchange doesn't slow down because your bidder is slow.
//
// Rate is set to 100 RPS — below our single-event-loop saturation point (~120 RPS)
// so we can measure stable latency without queueing artifacts.
export const options = {
    scenarios: {
        baseline: {
            executor: 'constant-arrival-rate',
            rate: 100,              // 100 requests/sec — within capacity
            timeUnit: '1s',
            duration: '2m',
            preAllocatedVUs: 50,
            maxVUs: 200,
        },
    },
    // Thresholds calibrated against Run 2 baseline observations (p50≈2ms, p95≈3ms,
    // p99≈4ms, max≈12ms, error=0%) with a margin large enough to absorb normal jitter
    // but tight enough to catch genuine regressions. abortOnFail=true so a regression
    // surfaces immediately rather than running the full 2-minute test.
    thresholds: {
        http_req_duration: [
            { threshold: 'p(50)<5',   abortOnFail: true },
            { threshold: 'p(95)<10',  abortOnFail: true },
            { threshold: 'p(99)<25',  abortOnFail: true },
            { threshold: 'p(99.9)<50', abortOnFail: false },
            { threshold: 'max<100',   abortOnFail: false },
        ],
        error_rate: [{ threshold: 'rate<0.001', abortOnFail: true }], // <0.1% (was <1%)
        bid_rate:   ['rate>0.85'],   // healthy catalog match — should be ≥85%
    },
};

export default function () {
    sendBidAndRecord(http);
}
