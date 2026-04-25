import http from 'k6/http';
import { sendBidAndRecord } from './helpers.js';

// Ramp test: gradually increase load to find the saturation point.
//
// How to read the results — look for the "knee":
// At low RPS, latency is flat (server has spare capacity).
// At some RPS, latency starts climbing sharply — that's the saturation point.
// Past saturation, every additional request adds queueing delay to ALL requests.
//
// ramping-arrival-rate changes the target requests/sec over time according to stages.
// If the server can't keep up as the rate ramps up, requests queue → latency climbs → we see the knee.
//
// Stages are calibrated for single-event-loop Vert.x with sync Redis.
// Expected saturation: ~100-150 RPS (sync Redis is the bottleneck).
export const options = {
    discardResponseBodies: true,
    scenarios: {
        ramp: {
            executor: 'ramping-arrival-rate',
            startRate: 10,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 5000,
            stages: [
                { target: 50, duration: '20s' },     // warmup
                { target: 50, duration: '30s' },     // hold — baseline latency
                { target: 100, duration: '20s' },    // ramp to 100 RPS
                { target: 100, duration: '30s' },    // hold — measure stable latency
                { target: 200, duration: '20s' },    // ramp to 200 RPS — likely near saturation
                { target: 200, duration: '30s' },    // hold — watch p99 climb
                { target: 500, duration: '20s' },    // push past saturation
                { target: 500, duration: '30s' },    // hold — expect degradation
                { target: 1000, duration: '20s' },   // extreme — queue explosion
                { target: 1000, duration: '30s' },   // hold — measure how bad it gets
                { target: 0, duration: '10s' },      // cooldown
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
