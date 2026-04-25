#!/usr/bin/env python3
"""
Seed Redis with 1 million users for Phase 17 production-scale load testing.

Generates RESP protocol directly (not plain-text commands) so redis-cli --pipe
can ingest at full network speed — typically 200K–500K users/sec vs ~30K/sec
for plain-text commands.

Usage (via Makefile — do not run directly):
    make seed-redis-1m

Equivalent manual command:
    python3 docker/seed-redis-1m.py | docker exec -i <redis-container> redis-cli --pipe
"""

import random
import sys
import os

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
SEG_BYTES = [s.encode() for s in SEGMENTS]
SEG_COUNT = len(SEGMENTS)

TOTAL = 1_000_000
FLUSH_EVERY = 50_000
SADD = b"SADD"

buf = bytearray()

for i in range(1, TOTAL + 1):
    key = ("user:user_%07d:segments" % i).encode()
    n = random.randint(3, 8)
    segs = random.sample(SEG_BYTES, n)

    # RESP inline: *{argc}\r\n $4\r\nSADD\r\n ${key_len}\r\n{key}\r\n ...
    argc = 2 + n
    buf += b"*" + str(argc).encode() + b"\r\n"
    buf += b"$4\r\nSADD\r\n"
    buf += b"$" + str(len(key)).encode() + b"\r\n" + key + b"\r\n"
    for seg in segs:
        buf += b"$" + str(len(seg)).encode() + b"\r\n" + seg + b"\r\n"

    if i % FLUSH_EVERY == 0:
        sys.stdout.buffer.write(buf)
        buf = bytearray()
        pct = i * 100 // TOTAL
        sys.stderr.write("\r  seeding %d%% (%s / %s users)..." % (pct, f"{i:,}", f"{TOTAL:,}"))
        sys.stderr.flush()

if buf:
    sys.stdout.buffer.write(buf)

sys.stderr.write("\r  seeding 100%% (%s / %s users)... done\n" % (f"{TOTAL:,}", f"{TOTAL:,}"))
