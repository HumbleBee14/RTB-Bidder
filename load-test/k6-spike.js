import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const bidRate = new Rate('bid_rate');
const noBidRate = new Rate('nobid_rate');
const errorRate = new Rate('error_rate');
const bidLatency = new Trend('bid_latency', true);

// Spike test: sudden 10x traffic burst, then immediate drop.
//
// Why this matters for RTB:
// Ad exchanges send traffic bursts when a popular page loads (Super Bowl homepage,
// breaking news, viral tweet). Your bidder sees 500 RPS → 5000 RPS in under a second.
// A well-built system absorbs the spike without crashing or losing requests.
// A poorly built one either: (a) queues requests past the SLA deadline (all timeouts),
// (b) runs out of threads/connections, or (c) OOMs from sudden allocation pressure.
//
// What to watch:
// - p99 during spike — does it blow past the 50ms SLA?
// - Error rate during spike — any connection resets or 500s?
// - Recovery time — how fast does p99 return to baseline after spike ends?
// - GC pauses during spike — does allocation pressure trigger a long pause?
export const options = {
    scenarios: {
        spike: {
            executor: 'ramping-arrival-rate',
            startRate: 500,
            timeUnit: '1s',
            preAllocatedVUs: 200,
            maxVUs: 3000,
            stages: [
                { target: 500, duration: '1m' },      // baseline: 500 RPS steady
                { target: 5000, duration: '5s' },      // SPIKE: 10x in 5 seconds
                { target: 5000, duration: '30s' },     // hold spike — stress test
                { target: 500, duration: '5s' },       // drop back to baseline
                { target: 500, duration: '1m' },       // recovery — watch p99 settle
            ],
        },
    },
    thresholds: {
        error_rate: ['rate<0.10'],  // tolerate up to 10% errors during spike
    },
};

const HOT_USER_COUNT = 1000;
const COLD_USER_COUNT = 9000;
const HOT_RATIO = 0.8;

const APP_CATEGORIES = ['sports', 'news', 'tech', 'gaming', 'entertainment', 'finance', 'food', 'travel'];
const DEVICE_TYPES = ['mobile', 'tablet', 'desktop'];
const DEVICE_OS = ['android', 'ios', 'windows'];
const GEOS = ['US', 'UK', 'DE', 'JP', 'IN', 'BR', 'AU', 'CA'];
const SLOT_SIZES = ['300x250', '728x90', '160x600', '320x50'];

function pickRandom(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function generateUserId() {
    if (Math.random() < HOT_RATIO) {
        return `user_${String(Math.floor(Math.random() * HOT_USER_COUNT) + 1).padStart(5, '0')}`;
    }
    return `user_${String(Math.floor(Math.random() * COLD_USER_COUNT) + HOT_USER_COUNT + 1).padStart(5, '0')}`;
}

function generateBidRequest() {
    const slotCount = Math.random() < 0.3 ? 2 : 1;
    const adSlots = [];
    for (let i = 0; i < slotCount; i++) {
        adSlots.push({
            id: `slot-${i + 1}`,
            sizes: [pickRandom(SLOT_SIZES)],
            bid_floor: Math.round((Math.random() * 0.8 + 0.1) * 100) / 100,
        });
    }

    return JSON.stringify({
        user_id: generateUserId(),
        app: {
            id: `app-${Math.floor(Math.random() * 50) + 1}`,
            category: pickRandom(APP_CATEGORIES),
            bundle: `com.${pickRandom(APP_CATEGORIES)}.app`,
        },
        device: {
            type: pickRandom(DEVICE_TYPES),
            os: pickRandom(DEVICE_OS),
            geo: pickRandom(GEOS),
        },
        ad_slots: adSlots,
    });
}

export default function () {
    const payload = generateBidRequest();
    const params = {
        headers: { 'Content-Type': 'application/json' },
    };

    const res = http.post('http://localhost:8080/bid', payload, params);
    bidLatency.add(res.timings.duration);

    check(res, {
        'status is 200 or 204': (r) => r.status === 200 || r.status === 204,
    });

    if (res.status === 200) {
        bidRate.add(true);
        noBidRate.add(false);
        errorRate.add(false);
    } else if (res.status === 204) {
        bidRate.add(false);
        noBidRate.add(true);
        errorRate.add(false);
    } else {
        bidRate.add(false);
        noBidRate.add(false);
        errorRate.add(true);
    }
}
