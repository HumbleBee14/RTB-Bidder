import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const bidRate = new Rate('bid_rate');
const noBidRate = new Rate('nobid_rate');
const errorRate = new Rate('error_rate');
const bidLatency = new Trend('bid_latency', true);

// Ramp test: gradually increase load to find the saturation point.
//
// How to read the results — look for the "knee":
// At low RPS, latency is flat (server has spare capacity).
// At some RPS, latency starts climbing sharply — that's the saturation point.
// Past saturation, every additional request adds queueing delay to ALL requests.
//
// constant-arrival-rate sends N requests/sec regardless of server speed.
// If the server can't keep up, requests queue → latency climbs → we see the knee.
//
// Stages are calibrated for single-event-loop Vert.x with sync Redis.
// Expected saturation: ~100-150 RPS (sync Redis is the bottleneck).
export const options = {
    scenarios: {
        ramp: {
            executor: 'ramping-arrival-rate',
            startRate: 10,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 1000,
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
    thresholds: {
        error_rate: ['rate<0.05'],
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
