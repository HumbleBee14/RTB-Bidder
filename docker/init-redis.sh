#!/bin/bash
# Seed Redis with 10K users, each with 3-8 random segments.
# Usage: docker exec -i redis redis-cli < docker/init-redis.sh
#    or: redis-cli < docker/init-redis.sh

SEGMENTS=(
  "sports" "tech" "travel" "finance" "gaming" "music" "food" "fashion"
  "health" "auto" "entertainment" "education" "news" "shopping" "fitness"
  "outdoor" "photography" "parenting" "pets" "home_garden"
  "age_18_24" "age_25_34" "age_35_44" "age_45_54" "age_55_plus"
  "male" "female"
  "high_income" "mid_income" "low_income"
  "urban" "suburban" "rural"
  "ios" "android" "desktop"
  "frequent_buyer" "deal_seeker" "brand_loyal" "new_user"
  "morning_active" "evening_active" "weekend_active"
  "video_viewer" "audio_listener" "reader"
  "commuter" "remote_worker" "student" "professional" "retired"
)

SEGMENT_COUNT=${#SEGMENTS[@]}

for i in $(seq 1 10000); do
  user_id="user_$(printf '%05d' $i)"
  # each user gets 3-8 random segments
  num_segments=$(( (RANDOM % 6) + 3 ))
  segment_args=""
  for j in $(seq 1 $num_segments); do
    idx=$(( RANDOM % SEGMENT_COUNT ))
    segment_args="$segment_args ${SEGMENTS[$idx]}"
  done
  echo "SADD user:${user_id}:segments $segment_args"
done

echo ""
echo "SELECT 0"
echo "DBSIZE"
