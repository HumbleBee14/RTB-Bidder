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

# Production: fixed heap + generational ZGC + GC log
JVM_PROD := $(JVM_BASE) \
            -XX:+ZGenerational \
            -Xms512m -Xmx512m \
            -XX:+AlwaysPreTouch \
            -Xlog:gc*:file=results/gc.log:time,uptime,level,tags

# Load-test: minimal logging so log I/O doesn't skew latency results
JVM_LOAD := $(JVM_PROD) -Drtb.log.level=WARN

# ── 1. First-time setup ───────────────────────────────────────────────────────

.PHONY: setup
setup: infra-up				## First-time setup: start all Docker services + seed Redis
	@echo "Waiting for Redis to be ready..."
	@attempts=0; \
	until docker exec $$(docker ps -qf name=redis) redis-cli ping 2>/dev/null | grep -q PONG; do \
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
	CAMPAIGNS_SOURCE=postgres EVENTS_TYPE=kafka \
	java $(JVM_PROD) -jar $(JAR)

.PHONY: run-load
run-load: build				## Build + run optimised for load testing (minimal logging)
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
seed-redis:				## Seed Redis with 10K test users (run once after infra-up)
	bash docker/init-redis.sh | docker exec -i $$(docker ps -qf name=redis) redis-cli

# ── 6. Verify / test ──────────────────────────────────────────────────────────

.PHONY: health
health:					## Check bidder health endpoint (bidder must be running)
	curl -s http://localhost:8080/health | jq .

.PHONY: bid
bid:					## Fire a sample bid request (bidder must be running)
	curl -s -X POST http://localhost:8080/bid \
	  -H "Content-Type: application/json" \
	  -d '{"user_id":"user_00001","app":{"id":"a1","category":"sports","bundle":"com.sports.app"},"device":{"type":"mobile","os":"android","geo":"US"},"ad_slots":[{"id":"slot-1","sizes":["300x250"],"bid_floor":0.10}]}' \
	  | jq .

.PHONY: test
test:					## Run unit tests
	$(MVN) test

# ── 7. Load testing ───────────────────────────────────────────────────────────

.PHONY: load-test-baseline
load-test-baseline:			## k6 baseline load test — 100 RPS constant for 2 min
	k6 run load-test/k6-baseline.js

.PHONY: load-test-ramp
load-test-ramp:				## k6 ramp test — 50 → 1000 RPS over 4 min
	k6 run load-test/k6-ramp.js

.PHONY: load-test-spike
load-test-spike:			## k6 spike test — sudden burst to 500 RPS
	k6 run load-test/k6-spike.js

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
