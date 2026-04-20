# Phase 12: Load Testing — Prove It with Numbers

## What was built

k6 load test scripts (baseline, ramp, spike), Redis seed script (10K users with random segments), ZGC log analysis. Three types of load tests ran against the full bidder stack (Vert.x + Redis + Kafka) to establish baseline latency, find the saturation point, and test spike resilience.

## Types of load tests and why each exists

### 1. Baseline (constant load)

**What**: Fixed request rate for a sustained period. Our test: 100 RPS for 2 minutes.

**Why**: Establishes the "steady-state" latency — what your system does when it has spare capacity. This is your reference point. If baseline p99 is 20ms, and under higher load p99 becomes 200ms, you know the 180ms increase is queueing delay from overload, not inherent slowness.

**k6 executor**: `constant-arrival-rate` — fires exactly N requests/sec regardless of how long each takes. If you use `ramping-vus` instead, VUs that are blocked waiting for responses can't send new requests, so actual RPS drops as the server slows down. This hides backpressure. `constant-arrival-rate` exposes it.

### 2. Ramp (gradual increase)

**What**: Increase request rate in steps, holding each step long enough to measure stable percentiles. Our test: 50 → 100 → 200 → 500 → 1000 RPS over 4.5 minutes.

**Why**: Finds the **saturation point** (the "knee" in the latency curve). At low RPS, latency is flat. At some RPS, latency starts climbing sharply — the server's processing capacity is exceeded, and requests queue. Past the knee, every additional request adds queueing delay to ALL requests. Finding the knee tells you: "this system handles up to X RPS before degradation."

**How to read it**: Look at p99 at each step. When p99 doubles between steps, you've found the knee.

### 3. Spike (sudden burst)

**What**: Stable baseline, then sudden 10x traffic increase, then drop back to baseline. Our test: 50 RPS → 500 RPS in 5 seconds → hold 30 seconds → drop back.

**Why**: Ad exchanges send burst traffic when popular pages load (Super Bowl homepage, breaking news). Your bidder sees stable traffic → 10x in under a second. The baseline must be within capacity (50 RPS, not 500) so you can observe the transition from "healthy" to "under pressure" and measure recovery time. If your baseline is already past saturation, you can't distinguish spike behavior from normal overload. What you watch:
- Does the system crash? (Connection resets, OOM)
- Does p99 blow past the SLA deadline? (50ms in our case)
- How fast does p99 recover after the spike ends?
- Do GC pauses spike from sudden allocation pressure?

## Key concepts for load testing

### Coordinated omission

The most common load testing mistake. If your load generator waits for a response before sending the next request, a slow response delays the NEXT request. This means the generator slows down when the server slows down — you see artificially good latency numbers because you're testing fewer requests during the slow period. k6's `constant-arrival-rate` avoids this by sending requests on a fixed schedule regardless of response time.

### Why percentiles, not averages

Average latency hides spikes. If 99 requests take 1ms and 1 request takes 1000ms, the average is 10.9ms — looks fine. But 1% of your users waited a full second. In RTB, a late response is worse than no response (the exchange already moved on), so p99 and p99.9 matter far more than average.

### Throughput vs latency (the tradeoff)

You can always get higher throughput by accepting higher latency (queueing). The useful metric is: "at what throughput does latency become unacceptable?" For RTB, unacceptable = above 50ms SLA. So our question is: "up to what RPS can we keep p99 < 50ms?"

### Warmup matters

JIT compilation in the JVM means the first few thousand requests are slow — the bytecode hasn't been compiled to native yet. Always warm up before measuring. We send 500 requests before starting any load test.

## Test environment

```
Hardware:  Windows 10, Intel i5-8th Gen, 32GB RAM
JVM:       Java 21, ZGC (generational), -Xms512m -Xmx512m, AlwaysPreTouch
Server:    Vert.x 4 on Netty, single event loop thread
Redis:     7-alpine in Docker, localhost
Kafka:     3.7.0 in Docker, localhost (NoOp publisher — events not sent)
Data:      10K users seeded in Redis, 10 campaigns in memory
Logging:   WARN level during tests (INFO logging blocks event loop — see findings)
```

## Results

### Baseline: 100 RPS, 2 minutes

```
Requests:     12,001 (100.0 RPS sustained, 0 dropped)
Error rate:   0.00%
Bid rate:     27% (73% no-bid — realistic for RTB)

Latency:
  p50:    6.49ms
  p90:    9.52ms
  p95:   11.02ms
  p99:   19.99ms
  p99.9: 73.90ms
  max:   90.76ms

VUs needed:   max 4 (server has massive spare capacity at this rate)
```

At 100 RPS, the bidder easily handles all traffic within the 50ms SLA. p99 = 20ms leaves 30ms of headroom. The 74ms p99.9 outlier is likely a ZGC pause or Redis timeout.

### Ramp: 50 → 100 → 200 → 500 → 1000 RPS

```
Total requests: 33,553
Error rate:     0.00%
Bid rate:       37%

Aggregate latency (biased by high-RPS queueing):
  p50:  5.38s    ← queueing at high stages dominates the aggregate
  p99:  7.85s

Dropped iterations: 54,046 (server couldn't keep up past ~120 RPS)
```

**The saturation point is ~120 RPS** on this machine. Past that, requests queue on the event loop and latency climbs linearly. At 200 RPS, most requests are in the seconds. At 1000 RPS, queue delay reaches 8+ seconds.

### Why ~120 RPS is the ceiling (and what would fix it)

**Root cause**: synchronous Redis calls on the Vert.x event loop.

Prometheus metrics during the ramp test:
```
FrequencyCap stage:  111.9 seconds total / 12,745 calls = 8.7ms average
CandidateRetrieval:    0.1 seconds total / 12,798 calls = 0.01ms average
BudgetPacing:          0.07 seconds total / 7,308 calls  = 0.01ms average
```

FrequencyCap does sync Redis `GET` per candidate. With 5-8 candidates per request, that's 5-8 sequential Redis roundtrips on the single event loop thread. At ~1ms per Redis call × 7 calls = 7ms/request of blocking I/O. Add SMEMBERS for user segments = ~9ms total blocking per request. Single-threaded max = 1000ms / 9ms ≈ 111 RPS. That matches exactly what we see.

**How production bidders solve this**:
1. **Async Redis** — Lettuce `async()` or `reactive()` commands. Redis calls don't block the event loop; responses arrive via callback. 10x throughput improvement.
2. **Redis pipelining** — batch all `GET` calls for frequency capping into a single Redis pipeline. 7 sequential roundtrips → 1 roundtrip with 7 commands.
3. **Multiple event loop threads** — Vert.x can run N event loops (one per core). Each handles requests independently. 4 cores × 120 RPS = ~480 RPS.
4. **Local cache for hot users** — Zipfian traffic means 20% of users get 80% of requests. Cache their segments locally. Eliminates Redis call for majority of traffic.

These are Phase 15+ optimizations. The architecture supports all of them — interfaces (UserSegmentRepository, FrequencyCapper) make the swap transparent.

### ZGC Performance

```
GC cycles:     31 (over ~12 minutes of testing)
Max pause:     0.035ms (35 microseconds)
Avg pause:     0.019ms (19 microseconds)
Pause types:   Mark Start, Mark End, Relocate Start
```

ZGC delivered exactly what it promises: **sub-millisecond pauses**. Not sub-1ms — sub-0.1ms. The zero-alloc hot path (Phase 11) means GC has very little to collect. 31 GC cycles across 12 minutes of 100 RPS = ~72K requests with only 31 collections.

For comparison, G1GC on this same workload would show 5-20ms pauses. At p99 = 20ms, a single 20ms G1 pause would double the tail latency.

## First finding: INFO logging blocked the event loop

The very first load test ran with INFO logging (console + file + JSON — three appenders). Results:

```
Target:   500 RPS
Actual:   175 RPS (65% requests dropped)
p50:      1.23 seconds
p99:      2.33 seconds
```

Three logging appenders doing synchronous file I/O on every request on the event loop. At 500 RPS, that's 1500 log writes/sec blocking the single thread.

**Fix**: Made `com.rtb` log level configurable via system property (`-Drtb.log.level=WARN`). In production, you'd use async appenders (Logback `AsyncAppender`) so logging never blocks the event loop.

**Lesson**: Logging is I/O. On a single-threaded event loop, it blocks just like any other I/O. Test with production-representative logging levels, not dev defaults. Or better: use async appenders always.

## Files

| File | Purpose |
|------|---------|
| `load-test/helpers.js` | Shared request generation, metrics, and response checking |
| `load-test/k6-baseline.js` | Constant 100 RPS for 2 minutes — stable latency measurement |
| `load-test/k6-ramp.js` | Ramp 50 → 1000 RPS — find saturation point |
| `load-test/k6-spike.js` | 50 → 500 RPS spike — burst resilience test |
| `load-test/sample-bid-request.json` | Reference bid request format |
| `docker/init-redis.sh` | Seed 10K users with random segments into Redis |
| `src/main/resources/logback.xml` | Configurable log level for load tests |

**Generated locally** (gitignored — regenerated per environment):

| File | Purpose |
|------|---------|
| `results/baseline-results.txt` | Raw k6 baseline output |
| `results/ramp-results.txt` | Raw k6 ramp output |
| `results/gc.log` | ZGC log with pause times |

## How to run (step by step)

### 1. Start infrastructure

```bash
docker compose up -d
docker ps  # verify redis and kafka are running
```

### 2. Seed Redis with 10K users

```bash
# The init script generates SADD commands for 10K users with 3-8 random segments each
bash docker/init-redis.sh | docker exec -i <redis-container> redis-cli --pipe

# Verify seed data
docker exec <redis-container> redis-cli DBSIZE          # should be ~10000
docker exec <redis-container> redis-cli SMEMBERS user:user_00001:segments  # check one user
```

### 3. Build and start server with load-test JVM flags

```bash
mvn package -q -DskipTests

# Key JVM flags explained:
#   -XX:+UseZGC               → Use Z Garbage Collector (sub-ms pauses)
#   -XX:+ZGenerational        → Use generational mode (better for short-lived objects)
#   -Xms512m -Xmx512m        → Fixed heap size (no resize pauses)
#   -XX:+AlwaysPreTouch       → Touch all heap pages at startup (avoids page faults later)
#   -Xlog:gc*:file=...        → Write GC logs to file (for pause time analysis)
#   -Drtb.log.level=WARN      → Suppress per-request INFO logging (blocks event loop)

CONSOLE_ENABLED=false JSON_ENABLED=false \
java \
  -XX:+UseZGC -XX:+ZGenerational \
  -Xms512m -Xmx512m -XX:+AlwaysPreTouch \
  -Xlog:gc*:file=results/gc.log:time,uptime,level,tags \
  -Drtb.log.level=WARN \
  -jar target/rtb-bidder-1.0.0.jar

# Verify server is up
curl -s http://localhost:8080/health | python -m json.tool
```

### 4. Warmup (JIT compilation)

```bash
# Send 500 requests to trigger C2 JIT compilation of hot paths
# Without warmup, first few thousand requests are 10-50x slower (interpreted bytecode)
for i in $(seq 1 500); do
  curl -s -o /dev/null -X POST http://localhost:8080/bid \
    -H "Content-Type: application/json" \
    -d "{\"user_id\":\"user_$(printf '%05d' $((RANDOM % 10000 + 1)))\",\"app\":{\"id\":\"a1\",\"category\":\"sports\",\"bundle\":\"com.s\"},\"device\":{\"type\":\"mobile\",\"os\":\"android\",\"geo\":\"US\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.10}]}" &
  if (( i % 50 == 0 )); then wait; fi
done
wait
echo "Warmup done"
```

### 5. Run load tests

```bash
# Install k6 if needed: https://grafana.com/docs/k6/latest/set-up/install-k6/

# Default target: http://localhost:8080
# Override with -e TARGET_URL for remote testing (e.g., from MacBook to Windows):
#   k6 run -e TARGET_URL=http://192.168.1.x:8080 load-test/k6-baseline.js

# Baseline — stable latency at 100 RPS (2 minutes)
k6 run --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99),p(99.9)" \
  load-test/k6-baseline.js | tee results/baseline-results.txt

# Ramp — find saturation point (4.5 minutes)
k6 run --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99),p(99.9)" \
  load-test/k6-ramp.js | tee results/ramp-results.txt

# Spike — 10x burst resilience (2.5 minutes)
k6 run --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99),p(99.9)" \
  load-test/k6-spike.js | tee results/spike-results.txt
```

### 6. Analyze GC pauses

ZGC logs every pause to `results/gc.log`. Each GC cycle has 3 pause events: Mark Start, Mark End, Relocate Start.

```bash
# Find top 5 longest GC pauses
grep "Pause" results/gc.log | awk '{print $NF}' | sort -t. -k1,1n -k2,2n | tail -5

# Count total GC cycles
grep -c "Pause Mark Start" results/gc.log

# See all pause durations (should ALL be sub-1ms with ZGC)
grep "Pause" results/gc.log | awk '{print $NF}' | sort -t. -k1,1n -k2,2n

# Check heap usage at end of test
grep -A5 "Heap Statistics" results/gc.log | tail -10
```

What you're looking for:
- **All pauses < 1ms** → ZGC is working correctly
- **All pauses < 0.1ms** → Zero-alloc hot path is keeping allocation pressure low
- **GC cycles not increasing under load** → No memory leak

### 7. Check Prometheus metrics for bottleneck analysis

```bash
# Check per-stage latency (find the slow stage)
curl -s http://localhost:8080/metrics | grep pipeline_stage_latency_seconds_sum

# Expected: FrequencyCap dominates (sync Redis calls)
# Example output:
#   pipeline_stage_latency_seconds_sum{stage="FrequencyCap"} 111.94  ← bottleneck
#   pipeline_stage_latency_seconds_sum{stage="CandidateRetrieval"} 0.12
#   pipeline_stage_latency_seconds_sum{stage="BudgetPacing"} 0.07

# Calculate average latency per stage:
#   sum / count = avg per call
#   111.94 / 12745 = 8.7ms per FrequencyCap call

# Check bid/no-bid counts
curl -s http://localhost:8080/metrics | grep -E "bid_requests_total|bid_responses_total|nobid"

# Check error counts (should be 0)
curl -s http://localhost:8080/metrics | grep bid_errors
```

## What these numbers mean for production

| Metric | Our result | Production target | Gap |
|--------|-----------|-------------------|-----|
| p99 latency at baseline | 20ms | <10ms | Async Redis would close this |
| Saturation point | ~120 RPS | 50K+ RPS | Async Redis + pipelining + multi-core |
| Error rate under load | 0% | <0.1% | Good — no crashes even past saturation |
| ZGC max pause | 0.035ms | <1ms | Exceeds target by 28x |
| Bid rate | 27% | 30-60% (varies) | Normal — depends on campaign inventory |
