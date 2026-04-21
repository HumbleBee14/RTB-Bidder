# RTB Bidder — Complete Manual

This is the operator's manual for the RTB bidder. Every section is a self-contained walkthrough: do step 1, check it worked, then step 2. No prior knowledge of the codebase needed.

---

## Table of Contents

- [Part A — First-time setup](#part-a--first-time-setup)
  - [A.1 Install prerequisites](#a1-install-prerequisites)
  - [A.2 Clone the repo](#a2-clone-the-repo)
  - [A.3 Build the app](#a3-build-the-app)
- [Part B — Quick start (5 minutes)](#part-b--quick-start-5-minutes)
- [Part C — Starting infrastructure](#part-c--starting-infrastructure)
  - [C.1 What each service is for](#c1-what-each-service-is-for)
  - [C.2 Start everything](#c2-start-everything)
  - [C.3 Start only what you need](#c3-start-only-what-you-need)
  - [C.4 Verify each service](#c4-verify-each-service)
- [Part D — Starting the bidder](#part-d--starting-the-bidder)
  - [D.1 Basic startup](#d1-basic-startup)
  - [D.2 Production-style startup](#d2-production-style-startup)
  - [D.3 Low-memory startup (laptops)](#d3-low-memory-startup-laptops)
  - [D.4 What successful startup looks like](#d4-what-successful-startup-looks-like)
- [Part E — Seeding test data](#part-e--seeding-test-data)
  - [E.1 Seed Redis with 10K users](#e1-seed-redis-with-10k-users)
  - [E.2 Campaigns (dummy vs real)](#e2-campaigns-dummy-vs-real)
- [Part F — Testing the app](#part-f--testing-the-app)
  - [F.1 Health check](#f1-health-check)
  - [F.2 Single bid request](#f2-single-bid-request)
  - [F.3 Multi-slot bid](#f3-multi-slot-bid)
  - [F.4 No-bid scenarios](#f4-no-bid-scenarios)
  - [F.5 Full event lifecycle (bid → win → impression → click)](#f5-full-event-lifecycle-bid--win--impression--click)
  - [F.6 Resilience test — kill Redis](#f6-resilience-test--kill-redis)
- [Part G — Running modes](#part-g--running-modes)
  - [G.1 Campaign source: JSON vs PostgreSQL](#g1-campaign-source-json-vs-postgresql)
  - [G.2 Events: NoOp vs Kafka](#g2-events-noop-vs-kafka)
  - [G.3 Targeting: segment, embedding, hybrid](#g3-targeting-segment-embedding-hybrid)
  - [G.4 Scoring: feature-weighted, ML, A/B, cascade](#g4-scoring-feature-weighted-ml-ab-cascade)
  - [G.5 Budget pacing: local, distributed, hourly, quality-throttled](#g5-budget-pacing-local-distributed-hourly-quality-throttled)
- [Part H — Logs](#part-h--logs)
  - [H.1 Where logs live](#h1-where-logs-live)
  - [H.2 Tailing logs live](#h2-tailing-logs-live)
  - [H.3 Changing log level](#h3-changing-log-level)
  - [H.4 Disabling log appenders](#h4-disabling-log-appenders)
- [Part I — Metrics (Prometheus)](#part-i--metrics-prometheus)
  - [I.1 Viewing raw metrics](#i1-viewing-raw-metrics)
  - [I.2 Opening Prometheus UI](#i2-opening-prometheus-ui)
  - [I.3 Key PromQL queries](#i3-key-promql-queries)
- [Part J — Dashboard (Grafana)](#part-j--dashboard-grafana)
  - [J.1 Opening Grafana](#j1-opening-grafana)
  - [J.2 Understanding each panel](#j2-understanding-each-panel)
  - [J.3 What to do when panels say "No data"](#j3-what-to-do-when-panels-say-no-data)
- [Part K — Analytics (ClickHouse)](#part-k--analytics-clickhouse)
  - [K.1 Setup](#k1-setup)
  - [K.2 Useful queries](#k2-useful-queries)
- [Part L — Load testing](#part-l--load-testing)
  - [L.1 Preparation](#l1-preparation)
  - [L.2 Warm up the JIT](#l2-warm-up-the-jit)
  - [L.3 Run baseline test](#l3-run-baseline-test)
  - [L.4 Run ramp test](#l4-run-ramp-test)
  - [L.5 Run spike test](#l5-run-spike-test)
  - [L.6 Analyze results](#l6-analyze-results)
- [Part M — Troubleshooting](#part-m--troubleshooting)
- [Part N — Shutting down](#part-n--shutting-down)
- [Part O — Full command cheat sheet](#part-o--full-command-cheat-sheet)

---

# Part A — First-time setup

## A.1 Install prerequisites

Install these tools once. After this, you never need to install them again.

| Tool | Version | macOS | Windows | Linux |
|------|---------|-------|---------|-------|
| **Java** | 21+ | `brew install openjdk@21` | [Oracle JDK 21](https://www.oracle.com/java/technologies/downloads/) | `apt install openjdk-21-jdk` |
| **Docker Desktop** | latest | [docker.com](https://docker.com/products/docker-desktop) | [docker.com](https://docker.com/products/docker-desktop) | `apt install docker.io docker-compose-plugin` |
| **Git** | any | preinstalled / `brew install git` | [git-scm.com](https://git-scm.com/) | `apt install git` |
| **k6** (load tests only) | 0.50+ | `brew install k6` | `winget install k6` | [binary](https://github.com/grafana/k6/releases) |

**Verify everything is installed:**

```bash
java -version            # should show 21.x.x or higher
docker --version         # 20+
git --version
k6 version               # only if you plan to load test
```

If any command says "not found", re-install that tool and reopen your terminal.

---

## A.2 Clone the repo

```bash
git clone <repo-url>
cd <repo>/performance-engineering/level-9-project-rtb-bidder/java-rtb-bidder
```

**Verify:**
```bash
ls
# Should list: pom.xml, docker-compose.yml, src/, docs/, docker/, load-test/, etc.
```

---

## A.3 Build the app

First build downloads dependencies (~1 minute). Later builds are cached and take ~15 seconds.

**macOS / Linux:**
```bash
./mvnw package -q -DskipTests
```

**Windows (cmd / Git Bash):**
```bash
./mvnw.cmd package -q -DskipTests
```

**Verify:**
```bash
ls target/rtb-bidder-1.0.0.jar
# Should exist, ~130 MB (fat jar — includes all dependencies)
```

---

# Part B — Quick start (5 minutes)

The absolute minimum to see the app respond to a bid request.

**Step 1 — Start Redis** (the only core dependency):
```bash
docker compose up -d redis
```

**Step 2 — Seed Redis with test users:**
```bash
bash docker/init-redis.sh | docker exec -i $(docker ps -qf name=redis) redis-cli --pipe
```

**Step 3 — Start the bidder:**
```bash
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar
```

Leave this terminal running. Open a new terminal for the next step.

**Step 4 — Send a bid request:**
```bash
curl -X POST http://localhost:8080/bid \
  -H "Content-Type: application/json" \
  -d '{"user_id":"user_00001","app":{"id":"a1","category":"sports","bundle":"com.s"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"s1","sizes":["300x250"],"bid_floor":0.10}]}'
```

**What you should see:**
- HTTP 200 + JSON with a `bids` array → a matched bid
- HTTP 204 + `X-NoBid-Reason` header → no campaign matched this user

Either outcome means the app is working. You're done with the quick start.

---

# Part C — Starting infrastructure

## C.1 What each service is for

| Service | Port | Purpose | Required when |
|---------|------|---------|---------------|
| **redis** | 6379 | User segments, frequency capping | Always (core dependency) |
| **kafka** | 9092 | Event publishing (bid/win/impression/click) | `EVENTS_TYPE=kafka` |
| **postgres** | 5432 | Campaign storage | `CAMPAIGNS_SOURCE=postgres` |
| **clickhouse** | 8123, 9000 | Analytics on Kafka events | You want SQL analytics |
| **prometheus** | 9090 | Scrapes metrics, stores time series | You want the Grafana dashboard |
| **grafana** | 3000 | Visualizes Prometheus data | You want the Grafana dashboard |

## C.2 Start everything

```bash
docker compose up -d
```

**Verify all 6 containers are running:**
```bash
docker ps
# Should show 6 rows: redis, kafka, postgres, clickhouse, prometheus, grafana
```

## C.3 Start only what you need

```bash
# Just Redis (minimal — for basic bidder testing)
docker compose up -d redis

# Redis + Kafka (for event publishing)
docker compose up -d redis kafka

# Redis + Postgres (for production-style campaign loading)
docker compose up -d redis postgres

# Everything for analytics
docker compose up -d redis kafka postgres clickhouse

# Just monitoring (Prometheus + Grafana)
docker compose up -d prometheus grafana
```

## C.4 Verify each service

Run each of these to confirm the service is healthy:

**Redis:**
```bash
docker exec $(docker ps -qf name=redis) redis-cli PING
```
Expected output: `PONG`

**Kafka:**
```bash
docker exec $(docker ps -qf name=kafka) /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```
Expected: empty list (topics get created on first publish) or existing topic names.

**PostgreSQL:**
```bash
docker exec $(docker ps -qf name=postgres) psql -U rtb -c "SELECT count(*) FROM campaigns"
```
Expected output: `count` column with value `10`.

**ClickHouse:**
```bash
docker exec $(docker ps -qf name=clickhouse) clickhouse-client -d rtb --query "SHOW TABLES"
```
Expected: 12 tables (4 MergeTree + 4 Kafka engine + 4 materialized views).

If tables are missing, run the init manually:
```bash
docker exec -i $(docker ps -qf name=clickhouse) clickhouse-client -d rtb --multiquery \
  < docker/init-clickhouse.sql
```

**Prometheus:**
```bash
curl -s http://localhost:9090/-/ready
```
Expected: `Prometheus Server is Ready.`

**Grafana:**
```bash
curl -s http://localhost:3000/api/health
```
Expected: JSON with `"database":"ok"`.

---

# Part D — Starting the bidder

## D.1 Basic startup

```bash
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar
```

## D.2 Production-style startup

All the flags a real production deployment would use:

```bash
java \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms512m -Xmx512m \
  -XX:+AlwaysPreTouch \
  -Xlog:gc*:file=results/gc.log:time,uptime,level,tags \
  -jar target/rtb-bidder-1.0.0.jar
```

| Flag | Why it matters |
|------|----------------|
| `-XX:+UseZGC` | Z Garbage Collector — sub-millisecond pauses |
| `-XX:+ZGenerational` | Generational mode — better for our short-lived request objects |
| `-Xms=-Xmx` | Fixed heap — no resize pauses during operation |
| `-XX:+AlwaysPreTouch` | Touch all heap pages at startup — no page-fault jitter later |
| `-Xlog:gc*:file=...` | Write GC events to file for analysis |

## D.3 Low-memory startup (laptops)

If Docker + JVM are competing for RAM on your laptop:

```bash
java -XX:+UseZGC -Xms128m -Xmx128m -jar target/rtb-bidder-1.0.0.jar
```

## D.4 What successful startup looks like

After running the start command, you should see output ending with something like:

```
Connected to Redis: localhost:6379
Campaign source: json
Using JSON file campaign repository (default)
Loaded 10 campaigns from campaigns.json
Campaign cache refreshed: 10 active campaigns
Targeting type: segment
Scoring type: feature-weighted
FrequencyCapper connected to Redis: localhost:6379
Prometheus metrics registry initialized (with JVM + system metrics)
Circuit breakers: redis(failures=5, cooldown=10000ms), kafka(failures=5, cooldown=30000ms)
Pacing type: local
Event publisher: noop
Routes configured: /bid, /win, /impression, /click, /health, /metrics, /docs
RTB Bidder started on port 8080 | SLA: 50ms | stages: 8
```

The last line is the confirmation it's ready. Leave this terminal running.

**Quick health check** from another terminal:
```bash
curl http://localhost:8080/health
```

---

# Part E — Seeding test data

## E.1 Seed Redis with 10K users

Without user segments, the targeting engine can't match any campaigns, so every bid request returns no-bid.

**Seed:**
```bash
bash docker/init-redis.sh | docker exec -i $(docker ps -qf name=redis) redis-cli --pipe
```

**Verify:**
```bash
docker exec $(docker ps -qf name=redis) redis-cli DBSIZE
```
Expected: `10000` (or slightly more — includes some metadata keys)

**Inspect one user:**
```bash
docker exec $(docker ps -qf name=redis) redis-cli SMEMBERS user:user_00001:segments
```
Expected: 3-8 random segments like `sports`, `tech`, `male`, `age_25_34`.

## E.2 Campaigns (dummy vs real)

**Dummy (JSON file, default)** — 10 campaigns hardcoded in `src/main/resources/campaigns.json`:
Nike, TechCorp, TravelMax, FinanceHub, GameZone, FoodDelight, StyleCo, HealthPlus, AutoDrive, EduLearn.

No setup needed — the JSON file is on the classpath.

**Real (PostgreSQL)** — same 10 campaigns but loaded from a database.

```bash
# Start Postgres (init script runs automatically on first container start)
docker compose up -d postgres

# Verify seeded
docker exec $(docker ps -qf name=postgres) psql -U rtb -c "SELECT id, advertiser, budget FROM campaigns"
# Should show 10 rows

# Then run bidder with postgres mode
CAMPAIGNS_SOURCE=postgres java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar
```

**To add your own campaigns:**

Edit `src/main/resources/campaigns.json` (rebuild required), or insert into Postgres:

```bash
docker exec -it $(docker ps -qf name=postgres) psql -U rtb
```

Then paste:
```sql
INSERT INTO campaigns
  (id, advertiser, budget, bid_floor, target_segments, creative_sizes,
   creative_url, advertiser_domain, max_impressions_per_hour, value_per_click)
VALUES
  ('camp-011', 'YourCo', 5000.00, 0.50,
   ARRAY['sports','tech'], ARRAY['300x250'],
   'https://example.com/creative.html', 'yourco.com', 10, 2.00);
```

Restart the bidder to reload the cache.

---

# Part F — Testing the app

Make sure the bidder is running and Redis is seeded before running these.

## F.1 Health check

```bash
curl -s http://localhost:8080/health | python -m json.tool
```

Expected:
```json
{
  "status": "UP",
  "components": {
    "redis": {"status": "UP", "detail": "PONG"},
    "kafka": {"status": "UP", "detail": "1 node(s)"}
  }
}
```

If Kafka isn't running, the `kafka` component shows `"status":"DOWN"` and the overall status becomes `"DOWN"` (HTTP 503). Bidder still serves requests — this just means health reports correctly.

## F.2 Single bid request

```bash
curl -s -w "\nHTTP: %{http_code}\n" -X POST http://localhost:8080/bid \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "user_00001",
    "app": {"id":"app-1","category":"sports","bundle":"com.sports.app"},
    "device": {"type":"mobile","os":"android","geo":"US"},
    "ad_slots": [{"id":"slot-1","sizes":["300x250"],"bid_floor":0.10}]
  }'
```

**Two possible outcomes:**

**Outcome 1 — HTTP 200 + bid:**
```json
{"bids":[{"bid_id":"...","slot_id":"slot-1","ad_id":"camp-001","price":0.75,"width":300,"height":250,"creative_url":"...","tracking_urls":{...},"advertiser_domain":"nike.com"}]}
```

**Outcome 2 — HTTP 204 (no-bid):** No body. Check headers:
```bash
curl -s -D - -o /dev/null -X POST http://localhost:8080/bid ...   # -D prints headers
```
Look for `X-NoBid-Reason`. Common reasons: `NO_MATCHING_CAMPAIGN` (user segments don't match any campaign), `TIMEOUT` (pipeline too slow).

## F.3 Multi-slot bid

```bash
curl -s -X POST http://localhost:8080/bid \
  -H "Content-Type: application/json" \
  -d '{
    "user_id":"user_00001",
    "app":{"id":"a1","category":"sports","bundle":"com.s"},
    "device":{"type":"mobile","os":"android","geo":"US"},
    "ad_slots":[
      {"id":"slot-banner","sizes":["300x250"],"bid_floor":0.1},
      {"id":"slot-hero","sizes":["728x90"],"bid_floor":0.2}
    ]
  }' | python -m json.tool
```

Expected: 2 bids in the array, **different** `ad_id` values (multi-slot deduplication prevents showing the same campaign twice on one page).

## F.4 No-bid scenarios

Each of these should return HTTP 204:

**Unknown user:**
```bash
curl -s -D - -o /dev/null -X POST http://localhost:8080/bid \
  -H "Content-Type: application/json" \
  -d '{"user_id":"unknown-user","ad_slots":[{"id":"s1","sizes":["300x250"],"bid_floor":0.1}]}' \
  | grep X-NoBid-Reason
```
Expected header: `X-NoBid-Reason: NO_MATCHING_CAMPAIGN`

**Missing user_id:**
```bash
curl -s -D - -o /dev/null -X POST http://localhost:8080/bid \
  -H "Content-Type: application/json" \
  -d '{"ad_slots":[{"id":"s1","sizes":["300x250"],"bid_floor":0.1}]}' \
  | grep X-NoBid-Reason
```

**Bid floor too high** (no campaign can afford this slot):
```bash
curl -s -D - -o /dev/null -X POST http://localhost:8080/bid \
  -H "Content-Type: application/json" \
  -d '{"user_id":"user_00001","app":{"id":"a1","category":"sports","bundle":"com.s"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"s1","sizes":["300x250"],"bid_floor":999.0}]}' \
  | grep X-NoBid-Reason
```

## F.5 Full event lifecycle (bid → win → impression → click)

Requires `EVENTS_TYPE=kafka` for events to actually publish.

The tracking URLs returned in the bid response already contain `user_id`, `campaign_id`, `slot_id` as query params — that's how `TrackingHandler` publishes complete events without needing a bid-cache lookup. You can also extract those URLs directly from the bid response instead of constructing them by hand.

**Step 1 — Send bid, capture bid_id:**
```bash
BID_RESP=$(curl -s -X POST http://localhost:8080/bid \
  -H "Content-Type: application/json" \
  -d '{"user_id":"user_00001","app":{"id":"a1","category":"sports","bundle":"com.s"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"s1","sizes":["300x250"],"bid_floor":0.1}]}')

BID_ID=$(echo $BID_RESP | python -c "import sys,json; print(json.load(sys.stdin)['bids'][0]['bid_id'])")
echo "Got bid_id: $BID_ID"
```

**Step 2 — Simulate win notification:**
```bash
curl -s -X POST http://localhost:8080/win \
  -H "Content-Type: application/json" \
  -d "{\"bid_id\":\"$BID_ID\",\"campaign_id\":\"camp-001\",\"clearing_price\":0.75}"
```

**Step 3 — Simulate impression:**
```bash
curl -s "http://localhost:8080/impression?bid_id=$BID_ID&user_id=user_00001&campaign_id=camp-001&slot_id=s1"
```

**Step 4 — Simulate click:**
```bash
curl -s "http://localhost:8080/click?bid_id=$BID_ID&user_id=user_00001&campaign_id=camp-001&slot_id=s1"
```

**Verify events reached Kafka:**
```bash
docker exec $(docker ps -qf name=kafka) /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic bid-events --from-beginning --max-messages 5
```

## F.6 Resilience test — kill Redis

Demonstrates the circuit breaker.

**Step 1 — Kill Redis:**
```bash
docker stop $(docker ps -qf name=redis)
```

**Step 2 — Send 10 bid requests:**
```bash
for i in 1 2 3 4 5 6 7 8 9 10; do
  curl -s -w "Request $i: HTTP %{http_code}\n" -o /dev/null -X POST http://localhost:8080/bid \
    -H "Content-Type: application/json" \
    -d '{"user_id":"user_00001","app":{"id":"a1","category":"sports","bundle":"com.s"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"s1","sizes":["300x250"],"bid_floor":0.1}]}'
done
```

Expected: bidder stays up, returns 204. First few requests fail → circuit opens after 5 failures → subsequent requests fail fast without hitting Redis.

**Step 3 — Check circuit breaker state:**
```bash
curl -s http://localhost:8080/metrics | grep circuit_breaker_state
```
Expected: `circuit_breaker_state{name="redis"} 1.0` (OPEN).

**Step 4 — Restart Redis, wait for recovery:**
```bash
docker start $(docker ps -aqf name=redis)
sleep 15   # cooldown period
curl -s http://localhost:8080/metrics | grep circuit_breaker_state
```
Expected: state transitions to `2.0` (HALF_OPEN), then `0.0` (CLOSED) after a successful test call.

---

# Part G — Running modes

Set these as environment variables before starting the bidder.

**macOS/Linux syntax:**
```bash
CAMPAIGNS_SOURCE=postgres java -jar target/rtb-bidder-1.0.0.jar
```

**Windows cmd syntax:**
```cmd
set CAMPAIGNS_SOURCE=postgres && java -jar target/rtb-bidder-1.0.0.jar
```

**Windows PowerShell syntax:**
```powershell
$env:CAMPAIGNS_SOURCE="postgres"; java -jar target/rtb-bidder-1.0.0.jar
```

## G.1 Campaign source: JSON vs PostgreSQL

**JSON (default)** — 10 campaigns loaded from `campaigns.json` on classpath. No database needed.
```bash
CAMPAIGNS_SOURCE=json java -jar target/rtb-bidder-1.0.0.jar
```

**PostgreSQL** — 10 campaigns loaded from Postgres (auto-seeded from `docker/init-postgres.sql`).
```bash
CAMPAIGNS_SOURCE=postgres java -jar target/rtb-bidder-1.0.0.jar
```

Requires: `docker compose up -d postgres`.

## G.2 Events: NoOp vs Kafka

**NoOp (default)** — events logged at DEBUG level, not published anywhere. No Kafka needed.
```bash
EVENTS_TYPE=noop java -jar target/rtb-bidder-1.0.0.jar
```

**Kafka** — events published to 4 Kafka topics: `bid-events`, `win-events`, `impression-events`, `click-events`.
```bash
EVENTS_TYPE=kafka java -jar target/rtb-bidder-1.0.0.jar
```

Requires: `docker compose up -d kafka`.

## G.3 Targeting: segment, embedding, hybrid

```bash
TARGETING_TYPE=segment  java -jar ...   # default — user segments intersect campaign targets
TARGETING_TYPE=embedding java -jar ...  # semantic matching via pre-computed embeddings
TARGETING_TYPE=hybrid   java -jar ...   # segment first; embedding fallback for unmatched
```

Embedding mode requires `ml/campaign_embeddings.json` and `ml/word_embeddings.json` on the classpath.

## G.4 Scoring: feature-weighted, ML, A/B, cascade

```bash
SCORING_TYPE=feature-weighted java -jar ...   # default — linear formula
SCORING_TYPE=ml               java -jar ...   # XGBoost pCTR model via ONNX
SCORING_TYPE=abtest           java -jar ...   # 50/50 split ML vs linear
SCORING_TYPE=cascade          java -jar ...   # linear filter → ML rescore
```

ML mode requires `ml/pctr_model.onnx` and `ml/feature_schema.json`.

## G.5 Budget pacing: local, distributed, hourly, quality-throttled

**Base pacer** (choose one):
```bash
PACING_TYPE=local        java -jar ...   # default — AtomicLong, single-instance
PACING_TYPE=distributed  java -jar ...   # Redis DECRBY, multi-instance safe
```

**Hourly smoothing** (optional decorator — spreads daily budget across hours):
```bash
PACING_HOURLY_ENABLED=true PACING_HOURLY_HOURS=24 java -jar ...
```

**Quality throttling** (optional decorator — skips low-pCTR bids to save budget for high-value opportunities, Phase 15):
```bash
# Requires SCORING_TYPE=ml or cascade — thresholds assume real pCTR scores
SCORING_TYPE=ml \
  PACING_QUALITY_THROTTLING_ENABLED=true \
  PACING_QUALITY_THRESHOLD_LOW=0.05 \
  PACING_QUALITY_THRESHOLD_HIGH=0.20 \
  java -jar ...
```

Threshold behavior:
- score ≥ `high` → always spend (bid full)
- score < `low` → always skip (save budget)
- in between → probabilistic spend, linearly interpolated

Decorators stack: `Local → Hourly → QualityThrottled`. Each layer has one job.

---

# Part H — Logs

## H.1 Where logs live

Three log appenders run by default. All enabled unless disabled via env vars.

| Log | Path | Format | Use for |
|-----|------|--------|---------|
| Console | stdout | Human-readable, short timestamps | Dev terminal |
| File | `logs/rtb-bidder.log` | Human-readable, full timestamps | Post-mortem review |
| JSON | `logs/rtb-bidder.json` | Structured JSON | ELK / Loki / Datadog ingestion |

Files rotate daily + at 100 MB, keeping 30 days max 1 GB total.

## H.2 Tailing logs live

**Console**: visible in the terminal where you started the bidder.

**File:**
```bash
tail -f logs/rtb-bidder.log
```

**JSON (pretty-printed):**
```bash
tail -f logs/rtb-bidder.json | python -c "
import sys, json
for line in sys.stdin:
    try: d = json.loads(line); print(d.get('timestamp','?'), d.get('level','?'), d.get('message','?'))
    except: pass
"
```

**What to look for:**

| Log pattern | Meaning |
|-------------|---------|
| `Pipeline: [Stage1: 0.1ms, Stage2: 2ms, ...] total=5ms bid=true` | Successful bid, per-stage breakdown |
| `No-bid: reason=NO_MATCHING_CAMPAIGN` | Normal no-bid |
| `SLA timeout before stage: X` | Pipeline exceeded 50ms before stage X |
| `Circuit breaker [redis] CLOSED → OPEN` | Dependency failing, circuit tripped |
| `Stage failed: X — <error>` | Exception in stage X |

## H.3 Changing log level

Default is `INFO`. For load testing or quiet operation, set to `WARN`:

```bash
java -Drtb.log.level=WARN -jar target/rtb-bidder-1.0.0.jar
```

Valid levels: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`.

**Why this matters:** INFO level logs every request (thousands per second under load). File I/O on the Vert.x event loop blocks the server. Always use `WARN` for load tests.

## H.4 Disabling log appenders

```bash
# Disable console (file + JSON still written)
CONSOLE_ENABLED=false java -jar target/rtb-bidder-1.0.0.jar

# File only (no console, no JSON)
CONSOLE_ENABLED=false JSON_ENABLED=false java -jar target/rtb-bidder-1.0.0.jar

# Console only (no file writes — minimal I/O for benchmarking)
FILE_ENABLED=false JSON_ENABLED=false java -jar target/rtb-bidder-1.0.0.jar

# Custom log directory
LOG_DIR=/var/log/rtb java -jar target/rtb-bidder-1.0.0.jar
```

---

# Part I — Metrics (Prometheus)

## I.1 Viewing raw metrics

The bidder exposes Prometheus-format metrics at `/metrics`:

```bash
curl -s http://localhost:8080/metrics | less
```

**Filter to key metrics:**
```bash
curl -s http://localhost:8080/metrics | grep -E "^(bid_|pipeline_|jvm_|circuit_|frequency_|budget_)" | head -30
```

## I.2 Opening Prometheus UI

Start Prometheus if you haven't:
```bash
docker compose up -d prometheus
```

Open in browser: **http://localhost:9090**

Useful pages:
- **http://localhost:9090/targets** — Is Prometheus successfully scraping the bidder? Should show status `UP`.
- **http://localhost:9090/graph** — Run any PromQL query, see graph.

## I.3 Key PromQL queries

Copy-paste these into the Prometheus graph UI or Grafana:

```promql
# Requests per second
rate(bid_requests_total[1m])

# p99 latency in milliseconds
bid_latency_seconds{quantile="0.99"} * 1000

# Fill rate (fraction of requests that got a bid)
bid_fill_rate

# Error rate (should be 0)
rate(bid_responses_total{outcome="error"}[1m])

# Timeout rate (SLA breaches)
rate(bid_responses_total{outcome="timeout"}[1m])

# Average latency per pipeline stage (in ms)
rate(pipeline_stage_latency_seconds_sum[1m])
  / rate(pipeline_stage_latency_seconds_count[1m]) * 1000

# Circuit breaker state (0=CLOSED healthy, 1=OPEN tripped, 2=HALF_OPEN recovering)
circuit_breaker_state

# JVM heap usage
jvm_memory_used_bytes{area="heap"}

# GC pause rate (seconds per second)
rate(jvm_gc_pause_seconds_sum[1m])
```

---

# Part J — Dashboard (Grafana)

## J.1 Opening Grafana

**Step 1 — Start Prometheus + Grafana:**
```bash
docker compose up -d prometheus grafana
```

**Step 2 — Open in browser:** http://localhost:3000

**Step 3 — Login:**
- Username: `admin`
- Password: `admin`
- (Or whatever you set in `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`)

On first login Grafana asks you to change the password. Click **Skip** for local dev.

**Step 4 — Find the dashboard:**

The **RTB Bidder — Live Dashboard** loads automatically as your home page.

If it doesn't, navigate manually:
- Click the three-line menu (top-left) → **Dashboards** → **Browse**
- Click **RTB Bidder — Live Dashboard**
- Or direct link: http://localhost:3000/d/rtb-bidder-main

**Step 5 — Controls:**
- **Time picker** (top-right): default "Last 30 minutes". Change to "Last 5 minutes" for live testing.
- **Refresh rate** (top-right, next to time picker): auto-refreshes every 10 seconds.
- **Panel → three dots → Edit**: inspect the PromQL for any panel.

## J.2 Understanding each panel

| Panel | What it shows | What normal looks like |
|-------|--------------|------------------------|
| **Bid QPS** | Requests/sec | Flat line at your load level |
| **Bid vs No-Bid Rate** | Stacked: bids, no-bids, timeouts, errors | Mostly bids + no-bids; no visible error layer |
| **Fill Rate** | Gauge, % requests that bid | 15-40% typical; red below 15%, green above 30% |
| **Latency Percentiles** | p50, p90, p99, p99.9 over time | p99 under 50ms (SLA); spikes = trouble |
| **Error & Timeout Rate** | Errors (red), timeouts (orange) | Flat zero in healthy state |
| **Pipeline Stage Latency** | Per-stage breakdown | FrequencyCap usually dominates (sync Redis) |
| **Frequency Cap & Budget Hits** | Cap hits, budget exhaustion | Low rates — high = revenue loss |
| **Circuit Breaker State** | CLOSED/OPEN/HALF_OPEN per dependency | All CLOSED (green) in healthy state |
| **JVM Heap Memory** | Used vs committed vs max | Used sawtooth pattern (GC), below max |
| **GC Pause Time** (2 panels) | Seconds/sec + count/min | Near-zero with ZGC |

## J.3 What to do when panels say "No data"

Common causes, in order of likelihood:

**1. Bidder isn't running.**
```bash
curl http://localhost:8080/health
```
If this fails → start the bidder (Part D).

**2. Prometheus can't reach the bidder.**

Open http://localhost:9090/targets — the `rtb-bidder` target should show `UP`. If `DOWN`:
- On Linux: make sure `extra_hosts: host.docker.internal:host-gateway` is in docker-compose (it is by default)
- Check bidder is listening: `netstat -an | grep 8080`

**3. No traffic has been sent.**

Counters are zero until requests come in. Send some:
```bash
for i in $(seq 1 50); do
  curl -s -o /dev/null -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"user_$(printf '%05d' $((RANDOM % 10000 + 1)))\",\"app\":{\"id\":\"a1\",\"category\":\"sports\",\"bundle\":\"com.s\"},\"device\":{\"type\":\"mobile\",\"os\":\"android\",\"geo\":\"US\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.10}]}"
done
```

Wait 15 seconds (one scrape interval) then refresh the dashboard.

**4. Time window is wrong.**

Top-right → change to **Last 5 minutes** (default is 30 min, which can show empty for a freshly-started bidder).

---

# Part K — Analytics (ClickHouse)

Requires `EVENTS_TYPE=kafka` so events actually flow to ClickHouse.

## K.1 Setup

```bash
# Start Kafka + ClickHouse
docker compose up -d kafka clickhouse

# Start bidder with Kafka events
EVENTS_TYPE=kafka java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Send traffic (events will flow automatically to ClickHouse via Kafka)
for i in $(seq 1 100); do
  curl -s -o /dev/null -X POST http://localhost:8080/bid ... # (full curl from Part F.2)
done
```

## K.2 Useful queries

Open ClickHouse shell:
```bash
docker exec -it $(docker ps -qf name=clickhouse) clickhouse-client -d rtb
```

Then run:

```sql
-- Total events by type
SELECT 'bid' AS type, count() FROM bid_events
UNION ALL SELECT 'win', count() FROM win_events
UNION ALL SELECT 'impression', count() FROM impression_events
UNION ALL SELECT 'click', count() FROM click_events;

-- Fill rate by hour
SELECT toStartOfHour(timestamp) AS hour,
       countIf(bid = 1) AS bids,
       count() AS total,
       round(bids / total * 100, 2) AS fill_rate_pct
FROM bid_events
WHERE timestamp > now() - INTERVAL 24 HOUR
GROUP BY hour ORDER BY hour;

-- Top campaigns by wins
SELECT campaign_id,
       count() AS wins,
       round(avg(clearing_price), 4) AS avg_price,
       round(sum(clearing_price), 2) AS total_spend
FROM win_events
GROUP BY campaign_id ORDER BY wins DESC LIMIT 10;

-- CTR by campaign (clicks / impressions)
SELECT i.campaign_id,
       count(DISTINCT i.bid_id) AS impressions,
       count(DISTINCT c.bid_id) AS clicks,
       round(count(DISTINCT c.bid_id) / count(DISTINCT i.bid_id) * 100, 4) AS ctr_pct
FROM impression_events i LEFT JOIN click_events c ON i.bid_id = c.bid_id
WHERE i.timestamp > now() - INTERVAL 24 HOUR
GROUP BY i.campaign_id ORDER BY ctr_pct DESC;

-- Latency percentiles over time
SELECT toStartOfMinute(timestamp) AS minute,
       quantile(0.50)(latency_ms) AS p50,
       quantile(0.99)(latency_ms) AS p99,
       quantile(0.999)(latency_ms) AS p999,
       count() AS requests
FROM bid_events
WHERE timestamp > now() - INTERVAL 1 HOUR
GROUP BY minute ORDER BY minute;

-- No-bid breakdown — why are we not bidding?
SELECT no_bid_reason, count() AS cnt,
       round(count() / sum(count()) OVER () * 100, 2) AS pct
FROM bid_events
WHERE bid = 0 AND timestamp > now() - INTERVAL 1 HOUR
GROUP BY no_bid_reason ORDER BY cnt DESC;
```

---

# Part L — Load testing

## L.1 Preparation

**Step 1 — Start infrastructure:**
```bash
docker compose up -d redis prometheus grafana
bash docker/init-redis.sh | docker exec -i $(docker ps -qf name=redis) redis-cli --pipe
```

**Step 2 — Start bidder in load-test mode** (minimal logging to avoid blocking event loop):
```bash
CONSOLE_ENABLED=false JSON_ENABLED=false \
  java -XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx512m -XX:+AlwaysPreTouch \
       -Xlog:gc*:file=results/gc.log:time,uptime,level,tags \
       -Drtb.log.level=WARN \
       -jar target/rtb-bidder-1.0.0.jar
```

Create the results directory if it doesn't exist:
```bash
mkdir -p results
```

## L.2 Warm up the JIT

Without JIT warmup, the first few thousand requests are 10-50x slower. Run this before any measurement:

```bash
for i in $(seq 1 500); do
  curl -s -o /dev/null -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
    -d "{\"user_id\":\"user_$(printf '%05d' $((RANDOM % 10000 + 1)))\",\"app\":{\"id\":\"a1\",\"category\":\"sports\",\"bundle\":\"com.s\"},\"device\":{\"type\":\"mobile\",\"os\":\"android\",\"geo\":\"US\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.10}]}" &
  if (( i % 50 == 0 )); then wait; fi
done
wait
echo "Warmup done"
```

## L.3 Run baseline test

**What it does:** 100 RPS constant for 2 minutes. Establishes stable latency numbers.

```bash
k6 run --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99),p(99.9)" \
  load-test/k6-baseline.js | tee results/baseline-results.txt
```

**What to look at:** the summary block at the end. Key numbers:
- `http_req_duration` p99 — should be under 50ms
- `error_rate` — should be 0%
- `http_reqs` — should be ~12000 (100 RPS × 120s)

## L.4 Run ramp test

**What it does:** Gradually ramps 50 → 1000 RPS over 4 minutes. Finds the saturation point.

```bash
k6 run --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99),p(99.9)" \
  load-test/k6-ramp.js | tee results/ramp-results.txt
```

**What to look at:** the **knee** — the RPS level at which p99 starts climbing sharply. Past this, the server is saturated and every additional request adds queueing delay.

## L.5 Run spike test

**What it does:** Stable 50 RPS → sudden spike to 500 RPS (10x) → hold 30s → drop back. Tests burst resilience.

```bash
k6 run --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99),p(99.9)" \
  load-test/k6-spike.js | tee results/spike-results.txt
```

**What to look at:**
- Error rate during spike (should stay under 10%)
- How fast p99 recovers after spike ends

## L.6 Analyze results

**GC pause times** (should all be sub-millisecond with ZGC):
```bash
# Top 5 longest pauses
grep "Pause" results/gc.log | awk '{gsub(/ms$/, "", $NF); print $NF}' | sort -n | tail -5

# Total GC cycles
grep -c "Pause Mark Start" results/gc.log
```

**Find the slowest stage:**
```bash
curl -s http://localhost:8080/metrics | grep pipeline_stage_latency_seconds_sum
```
Divide `_sum` by `_count` for each stage → average latency per call. The dominant stage is your bottleneck.

**Run load test against remote bidder** (e.g., k6 on your laptop, bidder on another machine):
```bash
k6 run -e TARGET_URL=http://192.168.1.50:8080 load-test/k6-baseline.js
```

---

# Part M — Troubleshooting

## Bidder won't start

**`Port 8080 already in use`** — Previous bidder still running.
```bash
# Windows
taskkill //F //IM java.exe

# macOS/Linux
pkill -f rtb-bidder
```

**`Could not connect to Redis`** — Redis container not running.
```bash
docker compose up -d redis
```

**`Failed to connect to PostgreSQL` on Windows** — Known JDBC scram-sha-256 / Docker Desktop issue.

Use JSON mode (default):
```bash
CAMPAIGNS_SOURCE=json java -jar target/rtb-bidder-1.0.0.jar
```

PostgreSQL mode works correctly on macOS/Linux.

**`OutOfMemoryError` at startup** — Docker containers + JVM competing for RAM.

Reduce heap:
```bash
java -XX:+UseZGC -Xms128m -Xmx128m -jar target/rtb-bidder-1.0.0.jar
```

Or stop heavy containers:
```bash
docker compose stop kafka clickhouse
```

## Every bid returns 204 (no-bid)

Check the `X-NoBid-Reason` header:

| Reason | Fix |
|--------|-----|
| `NO_MATCHING_CAMPAIGN` | User has no segments in Redis — run `bash docker/init-redis.sh \| ...` |
| `ALL_FREQUENCY_CAPPED` | User over-exposed — `redis-cli --scan --pattern 'user:*:campaign:*' \| xargs redis-cli DEL` |
| `BUDGET_EXHAUSTED` | All campaigns out of budget — restart bidder to reload budgets |
| `TIMEOUT` | Pipeline over 50ms — check Redis latency, JIT warmup, check logs for slow stage |
| `INTERNAL_ERROR` | Exception — read the log stack trace |

## Throughput is low under load

Typical ceiling on a laptop: ~120 RPS (synchronous Redis on Vert.x event loop).

**Check if logging is the culprit:**
```bash
# Restart with quiet logs
CONSOLE_ENABLED=false JSON_ENABLED=false java -Drtb.log.level=WARN -jar target/rtb-bidder-1.0.0.jar
```

**Find the slow stage:**
```bash
curl -s http://localhost:8080/metrics | grep pipeline_stage_latency_seconds_sum
```
FrequencyCap usually dominates — each candidate does a sync Redis GET.

## Circuit breaker stuck OPEN

```bash
curl -s http://localhost:8080/metrics | grep circuit_breaker_state
```

Values: `0` (CLOSED healthy), `1` (OPEN tripped), `2` (HALF_OPEN recovering).

Wait for cooldown:
- Redis circuit: 10 seconds
- Kafka circuit: 30 seconds

After cooldown, it enters HALF_OPEN on the next request. If the test succeeds → CLOSED. If it fails → back to OPEN for another cooldown.

## Grafana dashboard panels say "No data"

See [Part J.3](#j3-what-to-do-when-panels-say-no-data) above.

---

# Part N — Shutting down

**Stop the bidder:**
Press `Ctrl+C` in the bidder's terminal.

Or from another terminal:
```bash
# Windows
taskkill //F //IM java.exe

# macOS/Linux
pkill -f rtb-bidder
```

**Stop Docker services:**
```bash
# Stop containers, keep data (next start is fast)
docker compose stop

# Stop + remove containers (keeps volumes)
docker compose down

# Nuke everything including data (next start re-seeds)
docker compose down -v
```

**Clean rebuild:**
```bash
./mvnw clean package -DskipTests
```

---

# Part O — Full command cheat sheet

### Build / compile
```bash
./mvnw package -q -DskipTests              # build fat jar
./mvnw clean package -DskipTests           # clean rebuild
./mvnw exec:java                           # run via Maven (dev mode, auto-reload)
./mvnw test                                # run unit tests
```

### Docker
```bash
docker compose up -d                       # start all services
docker compose up -d redis kafka           # start specific services
docker compose ps                          # list services
docker compose logs -f <service>           # tail service logs
docker compose stop                        # stop all, keep data
docker compose down -v                     # stop + wipe data
```

### Run bidder (all modes)
```bash
# Minimal (JSON campaigns, NoOp events)
java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Production-style
CAMPAIGNS_SOURCE=postgres EVENTS_TYPE=kafka \
  java -XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx512m -XX:+AlwaysPreTouch \
       -Xlog:gc*:file=results/gc.log \
       -jar target/rtb-bidder-1.0.0.jar

# Load-test mode (minimal logging)
CONSOLE_ENABLED=false JSON_ENABLED=false \
  java -XX:+UseZGC -Xms512m -Xmx512m -Drtb.log.level=WARN \
       -jar target/rtb-bidder-1.0.0.jar

# Low-memory
java -XX:+UseZGC -Xms128m -Xmx128m -jar target/rtb-bidder-1.0.0.jar
```

### Seed data
```bash
bash docker/init-redis.sh | docker exec -i $(docker ps -qf name=redis) redis-cli --pipe
docker exec -i $(docker ps -qf name=postgres) psql -U rtb < docker/init-postgres.sql
docker exec -i $(docker ps -qf name=clickhouse) clickhouse-client -d rtb --multiquery < docker/init-clickhouse.sql
```

### Verify
```bash
curl http://localhost:8080/health
curl http://localhost:9090/-/ready
curl http://localhost:3000/api/health
docker exec $(docker ps -qf name=redis) redis-cli PING
docker exec $(docker ps -qf name=postgres) psql -U rtb -c "\dt"
docker exec $(docker ps -qf name=clickhouse) clickhouse-client -d rtb --query "SHOW TABLES"
```

### Test
```bash
# Single bid
curl -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d '{"user_id":"user_00001","app":{"id":"a1","category":"sports","bundle":"com.s"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"s1","sizes":["300x250"],"bid_floor":0.1}]}'

# Load tests
k6 run load-test/k6-baseline.js
k6 run load-test/k6-ramp.js
k6 run load-test/k6-spike.js
```

### Observe
```bash
# Browsers
open http://localhost:3000      # macOS — Grafana
start http://localhost:3000     # Windows — Grafana
http://localhost:9090           # Prometheus UI
http://localhost:8080/docs      # API docs

# Logs
tail -f logs/rtb-bidder.log
tail -f logs/rtb-bidder.json

# Metrics
curl -s http://localhost:8080/metrics | grep -E "^(bid_|pipeline_|jvm_)" | head
```

---

## Further reading

Individual phase docs (`docs/phase-*.md`) explain the *why* behind each architectural decision. This manual covers the *how* — operational tasks only.
