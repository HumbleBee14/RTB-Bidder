import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const bidRate = new Rate('bid_rate');        // % of requests that returned a bid (HTTP 200)
const noBidRate = new Rate('nobid_rate');    // % of requests that returned no-bid (HTTP 204)
const errorRate = new Rate('error_rate');    // % of requests that failed (HTTP != 200/204)
const bidLatency = new Trend('bid_latency', true);

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

// 10K seeded users — Zipfian-like distribution: first 1K hit 80% of traffic
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
    const slotCount = Math.random() < 0.3 ? 2 : 1;  // 30% multi-slot
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
