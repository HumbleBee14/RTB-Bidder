# ──────────────────────────────────────────────────────────────────────────────
#  RTB Bidder — Makefile
#  Usage: make <target>   |   Run `make help` to see all targets.
# ──────────────────────────────────────────────────────────────────────────────

# ── .env auto-load ────────────────────────────────────────────────────────────
# Source .env (if present) and EXPORT every variable into Make's environment.
# This is what makes `FREQCAP_STORE=aerospike` in .env actually flip behaviour:
#   * docker-compose auto-reads COMPOSE_PROFILES from env → picks the aerospike
#     container without any --profile flag on the command line.
#   * java picks up FREQCAP_STORE / AEROSPIKE_PORT / etc via System.getenv()
#     (AppConfig prefers env over application.properties).
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

# Derive the docker-compose profile from FREQCAP_STORE so a single .env switch
# controls both "which container starts" and "which client the bidder uses."
# Empty profile = no aerospike container; "aerospike" = it starts.
ifeq ($(FREQCAP_STORE),aerospike)
    export COMPOSE_PROFILES := aerospike
endif

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
# Heap raised from 512m → 2g: under sustained 5K RPS, allocation rate (mostly
# LinkedHashMap iterators + AdCandidate + Object[] from sorting) outpaces ZGC
# with a 512m heap, producing 12K+ GC pauses in 3min. 2g gives ZGC enough
# headroom that pauses become rare instead of constant.
JVM_PROD := $(JVM_BASE) \
            -Xms2g -Xmx2g \
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
run-prod: build				## Build + run with prod JVM flags (heap, GC log). Source/events follow .env.
	@mkdir -p results
	java $(JVM_PROD) -jar $(JAR)

.PHONY: run-load
run-load: build				## Build + run with load-test JVM flags (heap, JFR, minimal logging). Source/events follow .env.
	@mkdir -p results
	CONSOLE_ENABLED=false JSON_ENABLED=false \
	java $(JVM_LOAD) -jar $(JAR)

.PHONY: run-prod-load
run-prod-load: run-load			## Alias for run-load (kept for muscle memory)

.PHONY: stop-bidder
stop-bidder:				## Stop any locally running bidder process on port 8080
	@PIDS=$$(lsof -tiTCP:8080 -sTCP:LISTEN -n -P); \
	if [ -n "$$PIDS" ]; then \
	  echo "Stopping bidder PID(s): $$PIDS"; \
	  kill $$PIDS; \
	  sleep 1; \
	  STILL=$$(lsof -tiTCP:8080 -sTCP:LISTEN -n -P); \
	  if [ -n "$$STILL" ]; then \
	    echo "Force stopping PID(s): $$STILL"; \
	    kill -9 $$STILL; \
	  fi; \
	else \
	  echo "No bidder process listening on :8080"; \
	fi


# ── 4. Docker infrastructure ──────────────────────────────────────────────────

.PHONY: infra-up
infra-up:				## Start all infra (Aerospike included automatically when FREQCAP_STORE=aerospike in .env)
	docker-compose up -d

.PHONY: infra-up-minimal
infra-up-minimal:			## Start Redis only (minimum to run the bidder)
	docker-compose up -d redis


.PHONY: infra-start
infra-start:				## Resume previously stopped containers (data still intact)
	docker-compose start

.PHONY: infra-stop
infra-stop:				## Pause ALL containers (including Aerospike if it ever ran) — data volumes kept, fast restart
	COMPOSE_PROFILES=aerospike docker-compose stop

.PHONY: infra-down
infra-down:				## Remove ALL containers (including Aerospike) — data volumes still kept
	COMPOSE_PROFILES=aerospike docker-compose down

.PHONY: infra-reset
infra-reset:				## ⚠ Remove ALL containers AND volumes — full wipe, re-seed needed
	COMPOSE_PROFILES=aerospike docker-compose down -v

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
reset-state:				## Wipe freq-cap counters in whichever stores are running. Skips stores that are down.
	@REDIS=$$(docker-compose ps -q redis 2>/dev/null); \
	if [ -n "$$REDIS" ] && docker exec $$REDIS redis-cli ping >/dev/null 2>&1; then \
	  COUNT=$$(docker exec $$REDIS sh -c 'redis-cli --scan --pattern "freq:*" | xargs -r redis-cli DEL' 2>/dev/null); \
	  echo "redis: wiped $${COUNT:-0} freq:* keys"; \
	fi; \
	AERO=$$(docker-compose ps -q aerospike 2>/dev/null); \
	if [ -n "$$AERO" ]; then \
	  docker exec $$AERO asinfo -v "truncate:namespace=rtb;set=freq" >/dev/null 2>&1 && echo "aerospike: rtb.freq truncated"; \
	fi; \
	echo "freq state reset"

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
# Each: reset freq state → run k6 (30s warmup + 3min measure at target rate) →
# save summary. Reset is store-agnostic and skips any store that isn't running.
# Thresholds apply ONLY to the measure window via {phase:measure} tag filter.

# Internal helper — every per-RPS target calls this with RATE
define run_stress_test
	@curl -s -o /dev/null --connect-timeout 1 http://localhost:8080/metrics || (echo "ERROR: bidder not responding on :8080. Start it with 'make run-prod-load' in another terminal." && exit 1)
	@$(MAKE) reset-state >/dev/null
	@mkdir -p results
	@TS=$$(date +%Y%m%d-%H%M%S); \
	STRESS_RATE=$1 STRESS_ABORT_ON_FAIL=$${STRESS_ABORT_ON_FAIL:-false} k6 run --summary-export results/k6-stress-$1-$$TS.json load-test/k6-stress.js
endef

.PHONY: load-test-stress-5k
load-test-stress-5k:			## k6 stress at 5,000 RPS (30s warmup + 3min measure)
	$(call run_stress_test,5000)

.PHONY: load-test-stress-10k
load-test-stress-10k:			## k6 stress at 10,000 RPS
	$(call run_stress_test,10000)

.PHONY: load-test-stress-15k
load-test-stress-15k:			## k6 stress at 15,000 RPS
	$(call run_stress_test,15000)

.PHONY: load-test-stress-20k
load-test-stress-20k:			## k6 stress at 20,000 RPS
	$(call run_stress_test,20000)

.PHONY: load-test-stress-25k
load-test-stress-25k:			## k6 stress at 25,000 RPS
	$(call run_stress_test,25000)

.PHONY: load-test-stress-50k
load-test-stress-50k:			## k6 stress at 50,000 RPS
	$(call run_stress_test,50000)

.PHONY: load-test-stress-100k
load-test-stress-100k:			## k6 stress at 100,000 RPS
	$(call run_stress_test,100000)

.PHONY: load-test-stress-burn
load-test-stress-burn:			## k6 BURN — 200,000 RPS, 5 min measure
	@curl -s -o /dev/null --connect-timeout 1 http://localhost:8080/metrics || (echo "ERROR: bidder not responding on :8080." && exit 1)
	@$(MAKE) reset-state >/dev/null
	@mkdir -p results
	@TS=$$(date +%Y%m%d-%H%M%S); \
	STRESS_RATE=200000 STRESS_DURATION=5m STRESS_ABORT_ON_FAIL=false k6 run --summary-export results/k6-stress-burn-$$TS.json load-test/k6-stress.js

.PHONY: load-test-stress
load-test-stress: load-test-stress-5k load-test-stress-10k load-test-stress-25k load-test-stress-50k	## Run 5k → 50k in sequence

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
	@grep -E '^[a-zA-Z0-9_-]+:.*##' Makefile \
	  | awk 'BEGIN {FS = ":.*##"}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
