import { Rate, Trend } from 'k6/metrics';
import { check } from 'k6';

// 1M seeded users — uniform distribution across the full population.
//
// Why uniform instead of Zipfian:
//   The Zipfian (80/20) distribution we used before concentrated 80% of traffic
//   on the top 1K users. With a 1-hour freq-cap window, those users exhausted
//   their caps within seconds, making 95%+ of requests no-bid. That tells us
//   nothing about pipeline throughput — it just measures how fast we can say "no".
//
//   Uniform distribution means every user is equally likely regardless of ID.
//   With 1M users and any realistic freq-cap (5–20 impressions/hour), a user
//   hit once at 500 RPS has a 1-in-1M chance of being hit again in the same
//   second. Freq-cap exhaustion is negligible, so the load test measures what
//   we actually care about: full-pipeline throughput under sustained load.
const USER_COUNT = 1_000_000;

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
    // Uniform pick from all 1M seeded users — 7-digit zero-padded to match Redis keys.
    const n = Math.floor(Math.random() * USER_COUNT) + 1;
    return `user_${String(n).padStart(7, '0')}`;
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
