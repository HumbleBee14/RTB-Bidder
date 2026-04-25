# ──────────────────────────────────────────────────────────────────────────────
#  RTB Bidder — Makefile
#  Usage: make <target>   |   Run `make help` to see all targets.
# ──────────────────────────────────────────────────────────────────────────────

JAR := target/rtb-bidder-1.0.0.jar
MVN := ./mvnw

# JVM flags applied to every `java -jar` invocation:
#   --sun-misc-unsafe-memory-access=allow  silence Netty zero-copy deprecation warning
#   --enable-native-access=ALL-UNNAMED     silence native DNS resolver warning
#   -XX:+UseZGC                            sub-millisecond GC pauses
JVM_BASE := --sun-misc-unsafe-memory-access=allow \
            --enable-native-access=ALL-UNNAMED \
            -XX:+UseZGC

# Production: fixed heap + generational ZGC (default since Java 24) + GC log
JVM_PROD := $(JVM_BASE) \
            -Xms512m -Xmx512m \
            -XX:+AlwaysPreTouch \
            -Xlog:gc*:file=results/gc.log:time,uptime,level,tags

# Load-test: minimal logging + Java Flight Recorder running continuously.
# JFR overhead is ~1-2% with settings=profile; dump anytime with `make jfr-dump`.
# maxage=15m caps the rolling buffer so we keep recent test data without unbounded growth.
JVM_LOAD := $(JVM_PROD) -Drtb.log.level=WARN \
            -XX:StartFlightRecording=settings=profile,name=bidder,maxage=15m,maxsize=512m,disk=true,dumponexit=true,filename=results/flight-exit.jfr

# ── 1. First-time setup ───────────────────────────────────────────────────────

.PHONY: setup
setup: infra-up				## First-time setup: start all Docker services + seed Redis
	@echo "Waiting for Redis to be ready..."
	@# Use docker-compose ps -q redis — resolves the service name regardless of
	@# container naming convention. 'docker ps -qf name=redis' ALSO matches
	@# redis-exporter, causing docker exec to receive two IDs and silently fail.
	@attempts=0; \
	until docker exec $$(docker-compose ps -q redis) redis-cli ping 2>/dev/null | grep -q PONG; do \
		attempts=$$((attempts + 1)); \
		if [ $$attempts -ge 60 ]; then \
			echo ""; \
			echo "ERROR: Redis did not start after 60 seconds. Check: docker-compose logs redis"; \
			exit 1; \
		fi; \
		printf "."; sleep 1; \
	done
	@echo " Redis ready."
	$(MAKE) seed-redis

# ── 2. Build ──────────────────────────────────────────────────────────────────

.PHONY: build
build:					## Build the fat JAR (skips tests)
	$(MVN) package -DskipTests -q

.PHONY: rebuild
rebuild: clean build			## Clean + full rebuild

.PHONY: clean
clean:					## Remove build artifacts
	$(MVN) clean -q

.PHONY: build-verbose
build-verbose:				## Build with full Maven output
	$(MVN) package -DskipTests

# ── 3. Run ────────────────────────────────────────────────────────────────────

.PHONY: run
run: build				## Build + run with default settings (JSON campaigns, NoOp events)
	java $(JVM_BASE) -jar $(JAR)

.PHONY: run-jar
run-jar:				## Run existing JAR without rebuilding
	java $(JVM_BASE) -jar $(JAR)

.PHONY: run-prod
run-prod: build				## Build + run in production mode (Postgres campaigns + Kafka events)
	@mkdir -p results
	CAMPAIGNS_SOURCE=postgres EVENTS_TYPE=kafka \
	java $(JVM_PROD) -jar $(JAR)

.PHONY: run-load
run-load: build				## Build + run optimised for load testing (JSON campaigns, minimal logging)
	@mkdir -p results
	CONSOLE_ENABLED=false JSON_ENABLED=false \
	java $(JVM_LOAD) -jar $(JAR)

.PHONY: run-prod-load
run-prod-load: build			## Build + run in full prod mode (Postgres + Kafka, minimal logging)
	@mkdir -p results
	CAMPAIGNS_SOURCE=postgres EVENTS_TYPE=kafka \
	CONSOLE_ENABLED=false JSON_ENABLED=false \
	java $(JVM_LOAD) -jar $(JAR)

# ── 4. Docker infrastructure ──────────────────────────────────────────────────

.PHONY: infra-up
infra-up:				## Start all 6 Docker services (Redis, Kafka, Postgres, ClickHouse, Prometheus, Grafana)
	docker-compose up -d

.PHONY: infra-up-minimal
infra-up-minimal:			## Start Redis only (minimum to run the bidder)
	docker-compose up -d redis

.PHONY: infra-start
infra-start:				## Resume previously stopped containers (data still intact)
	docker-compose start

.PHONY: infra-stop
infra-stop:				## Pause containers — data volumes kept, fast restart
	docker-compose stop

.PHONY: infra-down
infra-down:				## Remove containers — data volumes still kept
	docker-compose down

.PHONY: infra-reset
infra-reset:				## ⚠ Remove containers AND volumes — full wipe, re-seed needed
	docker-compose down -v

.PHONY: infra-status
infra-status:				## Show running container status
	docker-compose ps

# ── 5. Seed data ──────────────────────────────────────────────────────────────

.PHONY: seed-redis
seed-redis:				## Seed Redis with 1M users (~3s via RESP pipe; only seed we need)
	# docker-compose ps -q resolves by service name, not container name.
	# 'docker ps -qf name=redis' also matches redis-exporter and fails silently.
	@echo "Seeding 1M users into Redis via RESP pipe protocol..."
	python3 docker/seed-redis.py | docker exec -i $$(docker-compose ps -q redis) redis-cli --pipe
	@echo "Done. Verify: make redis-count"

.PHONY: redis-count
redis-count:				## Show how many user-segment keys are currently in Redis
	docker exec $$(docker-compose ps -q redis) redis-cli DBSIZE

.PHONY: jfr-dump
jfr-dump:				## Snapshot the current JFR recording to results/flight-<timestamp>.jfr (bidder must be running)
	@PID=$$(pgrep -f "rtb-bidder-1.0.0.jar" | head -1); \
	if [ -z "$$PID" ]; then echo "ERROR: bidder not running"; exit 1; fi; \
	TS=$$(date +%Y%m%d-%H%M%S); \
	OUT=results/flight-$$TS.jfr; \
	jcmd $$PID JFR.dump name=bidder filename=$$OUT > /dev/null && \
	echo "✓ dumped to $$OUT ($$(du -h $$OUT | cut -f1))"; \
	echo "open with: jmc $$OUT  (or convert to flame graph)"

.PHONY: reset-state
reset-state:				## Wipe Redis freq:* counters between stress runs (bidder must be stopped first; restart it manually after)
	@echo "=== resetting Redis state for a clean stress run ==="
	@CONTAINER=$$(docker-compose ps -q redis); \
	if [ -z "$$CONTAINER" ]; then echo "ERROR: Redis container not running. 'make infra-up' first."; exit 1; fi; \
	BEFORE_TOTAL=$$(docker exec $$CONTAINER redis-cli DBSIZE); \
	BEFORE_FREQ=$$(docker exec $$CONTAINER redis-cli --scan --pattern 'freq:*' | wc -l | tr -d ' '); \
	echo "before: $$BEFORE_TOTAL total keys, $$BEFORE_FREQ freq:* counters"; \
	if [ "$$BEFORE_FREQ" != "0" ]; then \
	  echo "wiping freq:* counters (xargs runs inside the container — fast)..."; \
	  docker exec $$CONTAINER sh -c 'redis-cli --scan --pattern "freq:*" | xargs -r redis-cli DEL > /dev/null'; \
	fi; \
	AFTER_TOTAL=$$(docker exec $$CONTAINER redis-cli DBSIZE); \
	AFTER_FREQ=$$(docker exec $$CONTAINER redis-cli --scan --pattern 'freq:*' | wc -l | tr -d ' '); \
	echo "after:  $$AFTER_TOTAL total keys, $$AFTER_FREQ freq:* counters"; \
	echo ""; \
	echo "Redis state clean. User-segment data preserved ($$AFTER_TOTAL keys)."; \
	echo "Now restart the bidder for a clean run:"; \
	echo "  → Ctrl-C in the bidder terminal, then 'make run-prod-load'"

# ── 6. Verify / test ──────────────────────────────────────────────────────────

.PHONY: health
health:					## Check bidder health endpoint (bidder must be running)
	curl -s http://localhost:8080/health | jq .

.PHONY: bid
bid:					## Fire a sample bid request (bidder must be running)
	curl -s -X POST http://localhost:8080/bid \
	  -H "Content-Type: application/json" \
	  -d '{"user_id":"user_0000001","app":{"id":"a1","category":"sports","bundle":"com.sports.app"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"slot-1","sizes":["300x250"],"bid_floor":0.10}]}' \
	  | jq .

.PHONY: test
test:					## Run unit tests
	$(MVN) test

# ── 7. Load testing ───────────────────────────────────────────────────────────

.PHONY: pre-warm
pre-warm:				## Prime the Caffeine segment cache — 500 RPS for 5 min before any stress test
	k6 run load-test/k6-prewarm.js

.PHONY: load-test-baseline
load-test-baseline:			## k6 baseline — 100 RPS constant for 2 min (sanity)
	k6 run load-test/k6-baseline.js

.PHONY: load-test-ramp
load-test-ramp:				## k6 ramp test — 50 → 5000 RPS over ~6 min (find knee)
	k6 run load-test/k6-ramp.js

.PHONY: load-test-spike
load-test-spike:			## k6 spike test — sudden burst to 500 RPS (recovery)
	k6 run load-test/k6-spike.js

# ── 7a. Stress (constant-arrival-rate per RPS — pure-load percentiles) ────────
# Each runs 30s warmup + 3min measurement at the target rate. Thresholds
# apply ONLY to the measurement window via {phase:measure} tag filter.

.PHONY: load-test-stress-5k
load-test-stress-5k:			## k6 stress — 5,000 RPS constant, 3 min measure
	STRESS_RATE=5000  k6 run load-test/k6-stress.js

.PHONY: load-test-stress-10k
load-test-stress-10k:			## k6 stress — 10,000 RPS constant, 3 min measure
	STRESS_RATE=10000 k6 run load-test/k6-stress.js

.PHONY: load-test-stress-25k
load-test-stress-25k:			## k6 stress — 25,000 RPS constant, 3 min measure
	STRESS_RATE=25000 k6 run load-test/k6-stress.js

.PHONY: load-test-stress-50k
load-test-stress-50k:			## k6 stress — 50,000 RPS constant, 3 min measure
	STRESS_RATE=50000 k6 run load-test/k6-stress.js

.PHONY: load-test-stress-100k
load-test-stress-100k:			## k6 stress — 100,000 RPS constant, 3 min measure (M5 Pro sweat zone)
	STRESS_RATE=100000 k6 run load-test/k6-stress.js

.PHONY: load-test-stress-burn
load-test-stress-burn:			## k6 BURN — 200,000 RPS, 5 min measure. No mercy. Either the bidder breaks or k6 does.
	STRESS_RATE=200000 STRESS_DURATION=5m k6 run load-test/k6-stress.js

.PHONY: load-test-stress
load-test-stress: load-test-stress-5k load-test-stress-10k load-test-stress-25k load-test-stress-50k	## All stress rates 5k → 50k

.PHONY: load-test-all
load-test-all:				## Full sequence: baseline → ramp → spike → stress 5k/10k/25k/50k
	$(MAKE) load-test-baseline
	$(MAKE) load-test-ramp
	$(MAKE) load-test-spike
	$(MAKE) load-test-stress

# ── 8. Logs ───────────────────────────────────────────────────────────────────

.PHONY: logs
logs:					## Tail the plain-text log live
	tail -f logs/rtb-bidder.log

.PHONY: logs-json
logs-json:				## Tail the structured JSON log live
	tail -f logs/rtb-bidder.json | jq .

# ── Help ──────────────────────────────────────────────────────────────────────

.PHONY: help
help:					## Show this help
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*##"}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
