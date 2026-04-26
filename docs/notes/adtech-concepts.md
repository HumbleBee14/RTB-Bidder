# Adtech Concepts

A reference for adtech-domain terms.

## Contents

- [User Segments](#user-segments)

---

## User Segments

Audience tags attached to a user. They describe *who the user is* in marketing terms (interests, demographics, intent) so campaigns can target the right people.

**Typical storage shape:**
```
user_id  →  { tag_1, tag_2, tag_3, ... }
```

Example:
```
a3f9c2e1-b7d4-4e8a-9c2f-7b1e6d3a8f04  →  { auto_intender, income_high_bracket, frequent_flyer_united, cart_abandoner_nike_shoes }
```

Stored in a fast read-store (Redis Set, Aerospike record, etc.). Each user maps to a bag of tag strings.

**Examples:**
| Segment | Used by |
|---|---|
| `frequent_flyer_united` | travel ads |
| `cart_abandoner_nike_shoes` | retargeting |
| `lookalike_audience_tesla_buyers` | ML-derived audiences |
| `in_market_homeloan_30day` | mortgage refi |
| `geo_us_west_metro` | regional targeting |

A production DSP fleet handles thousands of distinct segments.

**How a bidder uses them:**
A campaign declares targeting like `{auto_intender, income_high_bracket}`. On each bid request the bidder fetches the user's segments and checks the campaign's required tags are a subset. Match → eligible to bid; no match → skip.

**Where the data comes from (bidders don't compute segments):**
- Internal behavioral pipelines (Spark/Flink over event logs, clickstreams, conversions)
- DMPs (LiveRamp, Adobe Audience Manager, Salesforce, Lotame) selling third-party audience feeds
- Advertiser first-party data (CRM uploads, hashed-matched to user IDs)
- Data brokers (Acxiom, Experian, Nielsen)
- Lookalike modeling pipelines deriving new audiences from seed lists

All write into the segment store offline. The bidder is read-only on the hot path.

**Update cadence:**
| Source | Frequency |
|---|---|
| DMP batch files | hourly–daily |
| Internal behavioral pipeline | hourly |
| Real-time event stream (Kafka) | seconds |

**Important: segments are NOT in the bid request.** The exchange only sends a user identifier (cookie, device ID). Each DSP looks up that user's segments in its own private store. Segment data is the DSP's proprietary asset — better data = better targeting = competitive advantage.
