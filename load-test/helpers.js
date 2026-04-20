import { Rate, Trend } from 'k6/metrics';
import { check } from 'k6';

// 10K seeded users — Zipfian-like distribution: first 1K hit 80% of traffic.
// Real ad exchanges show similar patterns: a small set of high-traffic publishers
// generates the majority of bid requests.
const HOT_USER_COUNT = 1000;
const COLD_USER_COUNT = 9000;
const HOT_RATIO = 0.8;

const APP_CATEGORIES = ['sports', 'news', 'tech', 'gaming', 'entertainment', 'finance', 'food', 'travel'];
const DEVICE_TYPES = ['mobile', 'tablet', 'desktop'];
const DEVICE_OS = ['android', 'ios', 'windows'];
const GEOS = ['US', 'UK', 'DE', 'JP', 'IN', 'BR', 'AU', 'CA'];
const SLOT_SIZES = ['300x250', '728x90', '160x600', '320x50'];

export const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';

export const bidRate = new Rate('bid_rate');
export const noBidRate = new Rate('nobid_rate');
export const errorRate = new Rate('error_rate');
export const bidLatency = new Trend('bid_latency', true);

function pickRandom(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function generateUserId() {
    if (Math.random() < HOT_RATIO) {
        return `user_${String(Math.floor(Math.random() * HOT_USER_COUNT) + 1).padStart(5, '0')}`;
    }
    return `user_${String(Math.floor(Math.random() * COLD_USER_COUNT) + HOT_USER_COUNT + 1).padStart(5, '0')}`;
}

export function generateBidRequest() {
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

export function sendBidAndRecord(http) {
    const payload = generateBidRequest();
    const params = { headers: { 'Content-Type': 'application/json' } };

    const res = http.post(`${BASE_URL}/bid`, payload, params);
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
