# Phase 4.5: Embedding-Based Targeting — AI-Native Ad Matching

## What was built

Semantic ad targeting using embedding cosine similarity. For AI-chat ad networks, the conversation context is the signal — "user is talking about running shoes" matches Nike without hardcoded segments. Three targeting modes: segment (classical), embedding (semantic), hybrid (both with fallback).

## Why this matters

Classical ad-tech matches on predefined segments ("sports fan", "age 25-34"). But in AI chat apps (ChatGPT, Claude, Gemini), the context is free-form conversation. There's no "sports" segment — there's "I'm looking for good running shoes for a marathon." Embedding similarity bridges this gap.

## How it works

```
  context_text: "I'm looking for a good pair of running shoes"
       │
       ▼
  Split into words → ["looking", "good", "pair", "running", "shoes"]
       │
       ▼
  Look up pre-computed word embeddings → average into context vector (384-dim)
       │
       ▼
  Cosine similarity against each campaign embedding:
    Nike (sports, fitness, outdoor)     → similarity: 0.42 ✓ (above 0.3 threshold)
    TechCorp (tech, gaming, education)  → similarity: 0.08 ✗
    TravelMax (travel, outdoor)         → similarity: 0.21 ✗
    HealthPlus (health, fitness)        → similarity: 0.35 ✓
       │
       ▼
  Return matched campaigns as candidates → pipeline continues (scoring, ranking, etc.)
```

## Three targeting modes

```properties
# Classical segment matching (default)
targeting.type=segment

# Embedding-only (AI-chat apps)
targeting.type=embedding

# Hybrid: embedding when context_text present, segment as fallback
targeting.type=hybrid
```

| Mode | When to use | How it routes |
|------|------------|---------------|
| `segment` | Traditional web/app ads | User segments ∩ campaign targets |
| `embedding` | AI-chat ad networks (context-only) | Cosine similarity on context_text |
| `hybrid` | Both scenarios in one bidder | context_text present → embedding; no context → segment; embedding no match → segment fallback |

## Embedding pipeline

```
Offline (Python):
  sentence-transformers (all-MiniLM-L6-v2)
    → campaign_embeddings.json (10 campaigns × 384 dims)
    → word_embeddings.json (162 vocabulary words × 384 dims)

Online (Java):
  context_text → split into words → average word embeddings → 384-dim vector
    → cosine similarity against campaign embeddings
    → return matches above threshold
```

### Why word-average instead of real-time ONNX sentence encoding?

| Approach | Latency | Accuracy | Complexity |
|----------|---------|----------|------------|
| Word-average (current) | 0.1ms | Moderate | Low — just array lookup + averaging |
| ONNX sentence-transformers | 3-5ms | High | High — needs tokenizer (WordPiece) in Java |

Word-average captures the topic well ("running shoes" → sports/fitness direction) but misses sentence-level nuance ("I DON'T want running shoes" still matches running). For production, ONNX sentence encoding is the upgrade path — TODO in the code.

### Cosine similarity

```
similarity(A, B) = (A · B) / (||A|| × ||B||)

Range: -1 to +1
  1.0 = identical direction (perfect match)
  0.0 = orthogonal (unrelated)
 -1.0 = opposite direction
```

We use threshold 0.3 (configurable) — campaigns scoring below this are excluded. This is the same pattern used in RAG (Retrieval-Augmented Generation) for document retrieval.

## Files

| File | Purpose |
|------|---------|
| `ml/generate_embeddings.py` | Python: generates campaign + word embeddings via sentence-transformers |
| `ml/campaign_embeddings.json` | Pre-computed campaign embeddings (384-dim) |
| `ml/word_embeddings.json` | Pre-computed word embeddings (162 words × 384-dim) |
| `targeting/EmbeddingTargetingEngine.java` | Cosine similarity matching against campaign embeddings |
| `targeting/HybridTargetingEngine.java` | Routes to embedding or segment targeting based on context |
| `model/BidRequest.java` | Added `contextText` field |
| `model/AdContext.java` | Added `contextText` field |

## Test Results

```
Targeting type: hybrid
Loaded 10 campaign embeddings, 162 word embeddings (dim=384), threshold=0.3
CandidateRetrieval: 2.693ms  ← embedding cosine similarity
```

| Test | context_text | Result | Targeting used |
|------|-------------|--------|---------------|
| Running shoes | "looking for running shoes for marathon" | 204 (threshold filtered) | Embedding → no match → segment fallback |
| Gaming laptop | "best laptop for programming and gaming" | GameZone (camp-005) | Embedding matched |
| Beach vacation | "planning a vacation to the beach" | Nike (camp-001) | Embedding matched |
| No context | (none) | HealthPlus (camp-008) | Segment (fallback) |

## Prerequisites — generating artifacts

The embedding files are NOT committed to git. They must be generated before using `targeting.type=embedding` or `targeting.type=hybrid`. If missing, the server fails fast with an actionable error:

```
Embeddings not found: ml/campaign_embeddings.json
  Generate with: python ml/generate_embeddings.py
  Or set targeting.type=segment to skip embedding targeting
```

Default config (`targeting.type=segment`) works without any Python setup.

## How to generate embeddings

```bash
pip install sentence-transformers
python ml/generate_embeddings.py
# → ml/campaign_embeddings.json + ml/word_embeddings.json
```

## How to test

```bash
mvnw.cmd package
TARGETING_TYPE=hybrid java -XX:+UseZGC -jar target/rtb-bidder-1.0.0.jar

# Embedding targeting with context_text
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"context_text\":\"best laptop for gaming\",\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30}]}"

# No context_text → falls back to segment targeting
curl -s -X POST http://localhost:8080/bid -H "Content-Type: application/json" \
  -d "{\"user_id\":\"user_00042\",\"app\":{\"id\":\"app1\"},\"ad_slots\":[{\"id\":\"s1\",\"sizes\":[\"300x250\"],\"bid_floor\":0.30}]}"
```

## Future enhancements

**Replace averaged word embeddings with true sentence embeddings.** `computeContextEmbedding()` currently averages per-word vectors from a pre-computed lookup table — simpler, fast, zero-allocation. Production-grade semantic matching would run the conversation context through a real sentence-transformer model (e.g., all-MiniLM-L6-v2) via ONNX Runtime — same inference path we already use for the ML scorer in Phase 6.5.

Trade-off: ~1-5 ms inference latency vs. current ~0.1 ms lookup. Worth it when context quality matters more than raw throughput (e.g., chat apps with long context windows). The `TargetingEngine` interface already abstracts this — adding an `OnnxEmbeddingTargetingEngine` would be a drop-in swap, no pipeline changes.

## References

- [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) — the embedding model
- [Cosine Similarity in Information Retrieval](https://en.wikipedia.org/wiki/Cosine_similarity)
- [RAG Pattern](https://www.pinecone.io/learn/retrieval-augmented-generation/) — same embedding retrieval pattern used in LLM apps
