#!/bin/bash
# Seed Redis with 10K users for quick dev cycles.
# Uses Python3 — bash loops hit ~5K iter/sec, Python generates 200K+ lines/sec.
#
# Usage:
#   bash docker/init-redis.sh | docker exec -i <redis-container> redis-cli
#   bash docker/init-redis.sh | redis-cli
#
# User IDs use 7-digit zero-padded format (user_0000001 … user_0010000)
# so they are a strict subset of the 1M perf-test seed (see seed-redis-1m.py).

python3 - <<'EOF'
import random, sys

SEGMENTS = [
  "sports", "tech", "travel", "finance", "gaming", "music", "food", "fashion",
  "health", "auto", "entertainment", "education", "news", "shopping", "fitness",
  "outdoor", "photography", "parenting", "pets", "home_garden",
  "age_18_24", "age_25_34", "age_35_44", "age_45_54", "age_55_plus",
  "male", "female",
  "high_income", "mid_income", "low_income",
  "urban", "suburban", "rural",
  "ios", "android", "desktop",
  "frequent_buyer", "deal_seeker", "brand_loyal", "new_user",
  "morning_active", "evening_active", "weekend_active",
  "video_viewer", "audio_listener", "reader",
  "commuter", "remote_worker", "student", "professional", "retired"
]

lines = []
for i in range(1, 10001):
    user_id = "user_%07d" % i
    segs = random.sample(SEGMENTS, random.randint(3, 8))
    lines.append("SADD user:%s:segments %s" % (user_id, " ".join(segs)))
    if len(lines) >= 5000:
        sys.stdout.write("\n".join(lines) + "\n")
        lines = []
if lines:
    sys.stdout.write("\n".join(lines) + "\n")
EOF

echo "SELECT 0"
echo "DBSIZE"
