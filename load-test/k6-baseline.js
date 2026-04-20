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
    thresholds: {
        http_req_duration: ['p(50)<20', 'p(95)<50', 'p(99)<100'],
        error_rate: ['rate<0.01'],
    },
};

export default function () {
    sendBidAndRecord(http);
}
