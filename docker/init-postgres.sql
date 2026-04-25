-- Campaign schema for the RTB bidder.
-- Matches the Campaign record: id, advertiser, budget, bid_floor, target_segments,
-- creative_sizes, creative_url, advertiser_domain, max_impressions_per_hour, value_per_click.
--
-- target_segments and creative_sizes are TEXT arrays — PostgreSQL native array type.
-- No ORM, no JPA — we read these directly via JDBC and convert to Java Sets.

CREATE TABLE IF NOT EXISTS campaigns (
    id                      VARCHAR(64) PRIMARY KEY,
    advertiser              VARCHAR(255) NOT NULL,
    budget                  NUMERIC(12,2) NOT NULL,
    bid_floor               NUMERIC(6,2) NOT NULL,
    target_segments         TEXT[] NOT NULL,
    creative_sizes          TEXT[] NOT NULL,
    creative_url            TEXT NOT NULL,
    advertiser_domain       VARCHAR(255) NOT NULL,
    max_impressions_per_hour INT NOT NULL DEFAULT 10,
    value_per_click         NUMERIC(6,2) NOT NULL DEFAULT 1.00,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_campaigns_active ON campaigns(active) WHERE active = TRUE;

-- ── Anchor campaigns (named brands, hand-tuned for E2E tests) ────────────────
INSERT INTO campaigns (id, advertiser, budget, bid_floor, target_segments, creative_sizes, creative_url, advertiser_domain, max_impressions_per_hour, value_per_click)
VALUES
    ('camp-001', 'Nike',        5000.00, 0.75, ARRAY['sports','fitness','outdoor'],                ARRAY['300x250','728x90'],               'https://ads.example.com/creative/nike-run.html',         'nike.com',        5,  2.50),
    ('camp-002', 'TechCorp',    3000.00, 0.50, ARRAY['tech','gaming','education'],                 ARRAY['300x250','160x600'],               'https://ads.example.com/creative/techcorp-laptop.html',  'techcorp.com',    8,  1.80),
    ('camp-003', 'TravelMax',   8000.00, 1.20, ARRAY['travel','outdoor','photography'],            ARRAY['728x90','300x250','160x600'],      'https://ads.example.com/creative/travelmax-deal.html',   'travelmax.com',   3,  4.00),
    ('camp-004', 'FinanceHub', 10000.00, 1.50, ARRAY['finance','tech','professional'],             ARRAY['300x250','728x90'],               'https://ads.example.com/creative/financehub-invest.html','financehub.com',  4,  5.00),
    ('camp-005', 'GameZone',    2000.00, 0.30, ARRAY['gaming','tech','entertainment'],             ARRAY['300x250','320x50'],               'https://ads.example.com/creative/gamezone-play.html',    'gamezone.com',   12,  1.20),
    ('camp-006', 'FoodDelight', 1500.00, 0.40, ARRAY['food','health','shopping'],                  ARRAY['300x250','728x90'],               'https://ads.example.com/creative/fooddelight-fresh.html','fooddelight.com', 10,  1.50),
    ('camp-007', 'StyleCo',     4000.00, 0.80, ARRAY['fashion','shopping','entertainment'],        ARRAY['160x600','300x250'],               'https://ads.example.com/creative/styleco-trend.html',    'styleco.com',     6,  2.00),
    ('camp-008', 'HealthPlus',  6000.00, 0.60, ARRAY['health','fitness','food'],                   ARRAY['728x90','300x250'],               'https://ads.example.com/creative/healthplus-app.html',   'healthplus.com',  7,  3.00),
    ('camp-009', 'AutoDrive',   7000.00, 1.00, ARRAY['auto','tech','finance'],                     ARRAY['728x90','300x250','320x50'],      'https://ads.example.com/creative/autodrive-ev.html',     'autodrive.com',   5,  3.50),
    ('camp-010', 'EduLearn',    1000.00, 0.25, ARRAY['education','tech','parenting'],              ARRAY['300x250','160x600'],               'https://ads.example.com/creative/edulearn-course.html',  'edulearn.com',   15,  0.80)
ON CONFLICT (id) DO NOTHING;

-- ── Scale campaigns (990 synthetic advertisers, realistic parameter distributions)
-- Generated via generate_series so startup is instant and data is deterministic.
--
-- Distribution rationale mirrors real DSP inventory:
--   - budget: uniform $500–$15,000 (long tail of small buyers, few whales)
--   - bid_floor: $0.10–$2.00 (display CPM range for US/EU traffic)
--   - max_impressions_per_hour: 2–20 (tight caps for brand safety)
--   - target_segments: 2–4 segments drawn from the same pool as Redis users
--     so overlap is non-trivial and the targeting filter has work to do
--   - creative_sizes: mix of standard IAB units
DO $$
DECLARE
    all_segments TEXT[] := ARRAY[
        'sports','tech','travel','finance','gaming','music','food','fashion',
        'health','auto','entertainment','education','news','shopping','fitness',
        'outdoor','photography','parenting','pets','home_garden',
        'age_18_24','age_25_34','age_35_44','age_45_54','age_55_plus',
        'male','female',
        'high_income','mid_income','low_income',
        'urban','suburban','rural',
        'ios','android','desktop',
        'frequent_buyer','deal_seeker','brand_loyal','new_user',
        'morning_active','evening_active','weekend_active',
        'video_viewer','audio_listener','reader',
        'commuter','remote_worker','student','professional','retired'
    ];
    all_sizes TEXT[] := ARRAY['300x250','728x90','160x600','320x50','970x250','300x600'];
    verticals TEXT[] := ARRAY[
        'Retail','Finance','Health','Travel','Auto','Tech','Food','Fashion',
        'Entertainment','Education','Gaming','Sports','Home','Beauty','Telecom'
    ];
    i INT;
    seg_count INT;
    size_count INT;
    segs TEXT[];
    sizes TEXT[];
BEGIN
    FOR i IN 11..1000 LOOP
        -- pick 2–4 segments pseudo-randomly (deterministic for same i)
        seg_count := 2 + (i % 3);
        segs := ARRAY[
            all_segments[1 + (i * 7  % array_length(all_segments, 1))],
            all_segments[1 + (i * 13 % array_length(all_segments, 1))],
            all_segments[1 + (i * 19 % array_length(all_segments, 1))],
            all_segments[1 + (i * 31 % array_length(all_segments, 1))]
        ][1:seg_count];

        -- pick 1–2 creative sizes
        size_count := 1 + (i % 2);
        sizes := ARRAY[
            all_sizes[1 + (i * 3 % array_length(all_sizes, 1))],
            all_sizes[1 + (i * 5 % array_length(all_sizes, 1))]
        ][1:size_count];

        INSERT INTO campaigns (
            id, advertiser, budget, bid_floor,
            target_segments, creative_sizes, creative_url,
            advertiser_domain, max_impressions_per_hour, value_per_click
        ) VALUES (
            'camp-' || LPAD(i::text, 4, '0'),
            verticals[1 + (i % array_length(verticals, 1))] || 'Brand' || i,
            -- budget: cycles $500–$15,000 in 15 steps
            500.00 + (i % 30) * 500.00,
            -- bid_floor: $0.10–$2.00
            ROUND((0.10 + (i % 20) * 0.10)::numeric, 2),
            segs,
            sizes,
            'https://ads.example.com/creative/camp-' || LPAD(i::text, 4, '0') || '.html',
            'brand' || i || '.example.com',
            -- max impressions/hour: 2–20
            2 + (i % 19),
            -- value per click: $0.50–$5.00
            ROUND((0.50 + (i % 10) * 0.50)::numeric, 2)
        ) ON CONFLICT (id) DO NOTHING;
    END LOOP;
END $$;
