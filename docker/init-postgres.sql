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
-- Budgets sized for sustained high-RPS load tests (Run 3 targets 5K–50K RPS):
-- a $5K budget at $2.50 value/click exhausts in ~2K wins, which would happen in
-- under a minute at 50K RPS. Anchor budgets bumped to $50K–$100K range for headroom.
INSERT INTO campaigns (id, advertiser, budget, bid_floor, target_segments, creative_sizes, creative_url, advertiser_domain, max_impressions_per_hour, value_per_click)
VALUES
    ('camp-001', 'Nike',         50000.00, 0.75, ARRAY['sports','fitness','outdoor'],                ARRAY['300x250','728x90'],               'https://ads.example.com/creative/nike-run.html',         'nike.com',        50,  2.50),
    ('camp-002', 'TechCorp',     30000.00, 0.50, ARRAY['tech','gaming','education'],                 ARRAY['300x250','160x600'],               'https://ads.example.com/creative/techcorp-laptop.html',  'techcorp.com',    80,  1.80),
    ('camp-003', 'TravelMax',    80000.00, 1.20, ARRAY['travel','outdoor','photography'],            ARRAY['728x90','300x250','160x600'],      'https://ads.example.com/creative/travelmax-deal.html',   'travelmax.com',   30,  4.00),
    ('camp-004', 'FinanceHub',  100000.00, 1.50, ARRAY['finance','tech','professional'],             ARRAY['300x250','728x90'],               'https://ads.example.com/creative/financehub-invest.html','financehub.com',  40,  5.00),
    ('camp-005', 'GameZone',     20000.00, 0.30, ARRAY['gaming','tech','entertainment'],             ARRAY['300x250','320x50'],               'https://ads.example.com/creative/gamezone-play.html',    'gamezone.com',  120,  1.20),
    ('camp-006', 'FoodDelight',  15000.00, 0.40, ARRAY['food','health','shopping'],                  ARRAY['300x250','728x90'],               'https://ads.example.com/creative/fooddelight-fresh.html','fooddelight.com',100,  1.50),
    ('camp-007', 'StyleCo',      40000.00, 0.80, ARRAY['fashion','shopping','entertainment'],        ARRAY['160x600','300x250'],               'https://ads.example.com/creative/styleco-trend.html',    'styleco.com',     60,  2.00),
    ('camp-008', 'HealthPlus',   60000.00, 0.60, ARRAY['health','fitness','food'],                   ARRAY['728x90','300x250'],               'https://ads.example.com/creative/healthplus-app.html',   'healthplus.com',  70,  3.00),
    ('camp-009', 'AutoDrive',    70000.00, 1.00, ARRAY['auto','tech','finance'],                     ARRAY['728x90','300x250','320x50'],      'https://ads.example.com/creative/autodrive-ev.html',     'autodrive.com',   50,  3.50),
    ('camp-010', 'EduLearn',     10000.00, 0.25, ARRAY['education','tech','parenting'],              ARRAY['300x250','160x600'],               'https://ads.example.com/creative/edulearn-course.html',  'edulearn.com',  150,  0.80)
ON CONFLICT (id) DO NOTHING;

-- ── Scale campaigns (990 synthetic advertisers, realistic parameter distributions)
-- Generated via generate_series so startup is instant and data is deterministic.
--
-- Distribution mirrors real DSP inventory tiers:
--   - 5% "always-on" / broad-reach (every 20th i): 1 popular demographic segment,
--     huge budget ($500K–$1M), low bid floor, very high impression cap. These act
--     as fallback inventory for users whose narrow segments don't match anyone else,
--     and are the reason real DSPs maintain high fill rates.
--   - 95% mid-range targeted: 2–4 segments, $5K–$150K budget, $0.10–$2.00 bid floor,
--     max 50 impressions/hour. The bread-and-butter of an ad exchange.
--
-- Budgets sized for sustained 5K–50K RPS load tests (Run 3): a small mid-range
-- campaign at $5K with $1.50/click value/click exhausts in ~3.3K wins, which would
-- happen in <2 minutes at 50K RPS. The 10× bump from earlier ($500–$15K → $5K–$150K)
-- gives us comfortable headroom for full ramp + spike sequences.
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
    -- Demographic segments common across users — used by broad-reach campaigns
    -- so they can serve as fallback inventory for niche-segment users.
    popular_segments TEXT[] := ARRAY[
        'age_18_24','age_25_34','age_35_44','age_45_54','age_55_plus',
        'male','female','ios','android','desktop','urban','suburban','rural'
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

        -- ── Tier A: broad-reach "always-on" campaigns (every 20th iteration → ~50)
        IF i % 20 = 0 THEN
            -- Single popular demographic segment — matches a huge slice of users
            segs := ARRAY[popular_segments[1 + (i % array_length(popular_segments, 1))]];
            -- Standard sizes (300x250 / 728x90) so they're served on most slots
            sizes := ARRAY['300x250', '728x90'];
            INSERT INTO campaigns (
                id, advertiser, budget, bid_floor,
                target_segments, creative_sizes, creative_url,
                advertiser_domain, max_impressions_per_hour, value_per_click
            ) VALUES (
                'camp-' || LPAD(i::text, 4, '0'),
                'BroadReach' || i,
                500000.00 + (i % 10) * 50000.00,        -- $500K–$1M (huge fallback budget)
                ROUND((0.05 + (i % 5) * 0.05)::numeric, 2),  -- $0.05–$0.25 (low floor)
                segs,
                sizes,
                'https://ads.example.com/creative/camp-' || LPAD(i::text, 4, '0') || '.html',
                'broadreach' || i || '.example.com',
                500,                                     -- 500 imp/hr — high fallback cap
                ROUND((0.30 + (i % 3) * 0.10)::numeric, 2)  -- $0.30–$0.50 (low value/click, low margin)
            ) ON CONFLICT (id) DO NOTHING;
            CONTINUE;
        END IF;

        -- ── Tier B: mid-range targeted campaigns (95% of catalog)
        -- pick 2–4 segments pseudo-randomly (deterministic for same i).
        -- PostgreSQL grammar requires the array to live in a variable before slicing,
        -- so we build the full 4-element array first and then trim with [1:seg_count].
        seg_count := 2 + (i % 3);
        segs := ARRAY[
            all_segments[1 + (i * 7  % array_length(all_segments, 1))],
            all_segments[1 + (i * 13 % array_length(all_segments, 1))],
            all_segments[1 + (i * 19 % array_length(all_segments, 1))],
            all_segments[1 + (i * 31 % array_length(all_segments, 1))]
        ];
        segs := segs[1:seg_count];

        -- pick 1–2 creative sizes (same two-step pattern)
        size_count := 1 + (i % 2);
        sizes := ARRAY[
            all_sizes[1 + (i * 3 % array_length(all_sizes, 1))],
            all_sizes[1 + (i * 5 % array_length(all_sizes, 1))]
        ];
        sizes := sizes[1:size_count];

        INSERT INTO campaigns (
            id, advertiser, budget, bid_floor,
            target_segments, creative_sizes, creative_url,
            advertiser_domain, max_impressions_per_hour, value_per_click
        ) VALUES (
            'camp-' || LPAD(i::text, 4, '0'),
            verticals[1 + (i % array_length(verticals, 1))] || 'Brand' || i,
            -- budget: $5K–$150K (10× bumped from $500–$15K to survive sustained 50K RPS)
            5000.00 + (i % 30) * 5000.00,
            -- bid_floor: $0.10–$2.00 (typical display CPM range)
            ROUND((0.10 + (i % 20) * 0.10)::numeric, 2),
            segs,
            sizes,
            'https://ads.example.com/creative/camp-' || LPAD(i::text, 4, '0') || '.html',
            'brand' || i || '.example.com',
            -- max impressions/hour: 20–70 (was 2–20; bumped so sustained load doesn't trigger caps)
            20 + (i % 50),
            -- value per click: $0.50–$5.00
            ROUND((0.50 + (i % 10) * 0.50)::numeric, 2)
        ) ON CONFLICT (id) DO NOTHING;
    END LOOP;
END $$;
