import http from 'k6/http';
import { sendBidAndRecord } from './helpers.js';

// Spike test: sudden 10x traffic burst, then immediate drop.
//
// Why this matters for RTB:
// Ad exchanges send traffic bursts when a popular page loads (Super Bowl homepage,
// breaking news, viral tweet). Your bidder sees stable traffic → 10x in under a second.
// A well-built system absorbs the spike without crashing or losing requests.
// A poorly built one either: (a) queues requests past the SLA deadline (all timeouts),
// (b) runs out of threads/connections, or (c) OOMs from sudden allocation pressure.
//
// Baseline is 50 RPS (well within our ~120 RPS capacity) so we can observe:
// - Clean transition from "healthy" (50 RPS, low latency) to "under pressure" (500 RPS)
// - Recovery curve after spike ends — how fast p99 returns to baseline
// - Whether error rate stays acceptable during the burst
export const options = {
    discardResponseBodies: true,
    scenarios: {
        spike: {
            executor: 'ramping-arrival-rate',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 1000,
            maxVUs: 2000,
            stages: [
                { target: 50,  duration: '30s' },      // baseline: 50 RPS steady
                { target: 500, duration: '5s'  },      // SPIKE: 10x in 5 seconds
                { target: 500, duration: '45s' },      // hold spike — stress test
                { target: 50,  duration: '5s'  },      // drop back to baseline
                { target: 50,  duration: '30s' },      // recovery — watch p99 settle
            ],
        },
    },
    thresholds: {
        error_rate: ['rate<0.10'],  // tolerate up to 10% errors during spike
    },
};

export default function () {
    sendBidAndRecord(http);
}
