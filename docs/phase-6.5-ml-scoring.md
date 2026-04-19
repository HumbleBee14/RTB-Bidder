# Phase 6.5: ML Scoring — pCTR Prediction Model

## What was built

XGBoost pCTR (predicted Click-Through Rate) model trained offline in Python, exported to ONNX, loaded in Java via ONNX Runtime for real-time inference. ABTestScorer enables traffic splitting between FeatureWeightedScorer and MLScorer. Config-driven — switch scoring strategy with one property change, zero code changes.

## Why ML Scoring Over Formula

| Approach | How it works | Strength | Weakness |
|----------|-------------|----------|----------|
| FeatureWeightedScorer (Phase 6) | `overlap × price × pacing` | Interpretable, fast, no training data needed | Can't learn non-linear patterns (e.g. "sports user on gaming app at night") |
| MLScorer (Phase 6.5) | XGBoost pCTR prediction | Learns complex feature interactions from data | Needs training data, adds inference latency |

Production ad-tech (Moloco, Criteo, The Trade Desk) uses ML scoring exclusively. The formula is a starting point; the model is the product.

## Why XGBoost Over Deep Learning

| Model | Inference latency | Accuracy | Complexity |
|-------|------------------|----------|------------|
| XGBoost/LightGBM (GBDT) | 1-5ms per candidate | Good | Low — train with scikit-learn, export to ONNX |
| Deep Neural Network | 20-40ms per candidate | 2-3% better | High — GPU needed, larger model, harder to serve |

Production systems like Moloco and Adobe Advertising use GBDT for initial scoring. DNNs are only used for reranking the top-N candidates (Phase 2 optimization, 6-12 months into production). Our 50ms SLA can absorb 5ms for GBDT but not 40ms for a DNN.

Source: [Adobe: Behind the Curtain of a High-Performance Bidder](https://experienceleaguecommunities.adobe.com/t5/adobe-advertising-cloud-blogs/behind-the-curtain-of-a-high-performance-bidder-service/ba-p/765771)

## Why ONNX Runtime

ONNX (Open Neural Network Exchange) is the standard for cross-platform model serving:
- Train in Python (XGBoost, PyTorch, TensorFlow) → export to `.onnx` → serve in Java/C++/Rust
- ONNX Runtime is Microsoft's inference engine — used at Azure, Bing, Office scale
- Java API bundles the native `.dll/.so` — no separate install needed
- Supports graph optimizations (operator fusion, constant folding) for faster inference

## Architecture

```
  Training (offline, Python):
    Synthetic click data → XGBoost → export to ONNX → ml/pctr_model.onnx

  Serving (online, Java):
    ┌─ ScoringStage ────────────────────────────────────────────────┐
    │                                                               │
    │  Scorer scorer = createScorer(config);                        │
    │                                                               │
    │  scoring.type=feature-weighted  → FeatureWeightedScorer       │
    │  scoring.type=ml                → MLScorer (ONNX Runtime)     │
    │  scoring.type=abtest            → ABTestScorer                │
    │                                   ├─ 50% → FeatureWeightedScorer
    │                                   └─ 50% → MLScorer           │
    │                                                               │
    │  All implement Scorer interface. Pipeline doesn't know which. │
    └───────────────────────────────────────────────────────────────┘
```

## Scoring Formula

```
FeatureWeightedScorer:  score = segmentOverlap × bidFloor × pacingFactor
MLScorer:               score = pCTR × valuePerClick
```

MLScorer's score represents expected revenue per click: "what's the probability this user clicks this ad, times how much each click is worth." This is how real ad auctions work — bid = pCTR × value.

## Feature Vector (66 floats)

```
Index    Feature                    Type
[0-50]   user segments              one-hot (51 segments)
[51-60]  app category               one-hot (10 categories)
[61-63]  device type                one-hot (mobile/desktop/tablet)
[64]     hour of day                normalized (0.0 - 1.0)
[65]     campaign bid floor         float
```

All features are pre-computed — UserProfile segments already fetched from Redis, AdContext already parsed from request. The feature vector is assembled from existing pipeline data, no additional I/O.

## Performance Considerations

### Feature assembly: zero I/O on hot path

By the time ScoringStage runs, all data is already in memory:
- User segments: fetched in UserEnrichmentStage (Redis, shared)
- App category, device type: parsed from BidRequest
- Hour of day: `LocalTime.now()`
- Campaign bid floor: loaded at startup

Feature vector assembly is pure array writes — ~0.01ms.

### ONNX session: loaded once, reused across requests

`OrtSession` is created at startup with `OptLevel.ALL_OPT` (graph optimizations). The session is reused for all requests. Synchronized access ensures thread-safety — at higher QPS, a session pool (one per thread) eliminates contention.

### Inference budget: 1-5ms per candidate

With 3-5 candidates per request, total scoring is 5-25ms. Within our 50ms SLA alongside Redis calls (~15ms), this fits. If scoring becomes the bottleneck, we batch candidates into a single inference call (ONNX supports batch input).

## Training Details

```
Data:       100K synthetic click events
Positive:   5.6% CTR (realistic — industry average 2-5%)
Model:      XGBoost, 100 trees, depth 5
AUC:        0.5567 (expected with synthetic data — real click data would be 0.70-0.85)
Export:     ONNX with zipmap=False (plain float array output)
```

The model's accuracy is limited by synthetic data. With real impression+click logs from Phase 8 (Kafka events), we'd retrain on actual user behavior and see AUC 0.70+.

## A/B Testing

ABTestScorer splits traffic deterministically by user_id hash:
- Same user always gets the same scorer variant (no flickering)
- Configurable split: `scoring.abtest.treatment.percentage=50`
- Both variants log through the same pipeline — compare metrics side by side

## Files

| File | Purpose |
|------|---------|
| `ml/train_pctr_model.py` | Python: generate data, train XGBoost, export ONNX |
| `ml/pctr_model.onnx` | Trained model artifact (66 features → pCTR) |
| `ml/feature_spec.txt` | Feature ordering metadata |
| `scoring/MLScorer.java` | ONNX Runtime inference, feature vector assembly |
| `scoring/ABTestScorer.java` | Deterministic A/B split between two scorers |
| `model/Campaign.java` | Added `valuePerClick` field |

## Test Results and Analysis

### Scorer attribution in pipeline logs

```
# FeatureWeightedScorer:
Pipeline: [..., Scoring(FeatureWeightedScorer): 0.07ms, ...] bid=true

# MLScorer:
Pipeline: [..., Scoring(MLScorer): 0.92ms, ...] bid=true
```

Every pipeline log line shows which scorer produced the ranking — critical for A/B comparison.

### ML scoring produces different rankings

| Scorer | Winner for user_00042 (fitness, student, urban, deal_seeker, age_18_24, age_25_34) |
|--------|------------|
| FeatureWeightedScorer | HealthPlus (2/3 segment overlap × $0.60 = 0.40) |
| MLScorer | Nike (pCTR 0.045 × $2.50 valuePerClick = 0.1125) |

The ML model learned that "fitness user on sports app on mobile" has higher click probability for Nike than HealthPlus — a non-linear interaction the formula can't capture.

### Cold start vs warm performance

```
First request (cold):   Scoring(MLScorer): 8.39ms  ← ONNX JIT compilation
Second request (warm):  Scoring(MLScorer): 0.92ms  ← cached inference path
Third request:          Scoring(MLScorer): 0.87ms  ← stable
```

ONNX Runtime JIT-compiles the model graph on first inference. After warmup, scoring is <1ms per candidate — well within the 5ms per-candidate budget.

### Redis command timeout: bumped from 5ms to 50ms

Original 5ms timeout caused failures during cold starts. Analysis:
- First Redis call after JVM startup takes 7-10ms (TCP connection warmup, Nagle's algorithm)
- Subsequent calls: 2-3ms (connection reuse, warm path)
- 5ms was too aggressive even for warm calls on Windows Docker (network bridge overhead)
- 50ms gives headroom for cold starts while still failing fast on actual Redis outages
- In production on Linux with native Redis: 5ms would work fine

### ONNX Runtime version: 1.19.2 (not 1.21.0)

ONNX Runtime 1.21.0 DLL failed to load on Windows (`UnsatisfiedLinkError: DLL initialization routine failed`). Downgraded to 1.19.2 which loads cleanly. This is a known issue with newer ONNX Runtime builds on certain Windows configurations.

## How to configure

```properties
# Use formula scoring (default)
scoring.type=feature-weighted

# Use ML scoring
scoring.type=ml
scoring.ml.model.path=ml/pctr_model.onnx

# A/B test: 50% ML, 50% formula
scoring.type=abtest
scoring.abtest.treatment.percentage=50
```

## How to retrain

```bash
cd java-rtb-bidder
pip install xgboost scikit-learn skl2onnx onnxmltools numpy
python ml/train_pctr_model.py
# → ml/pctr_model.onnx updated, restart server to load
```

## References

- [Moloco DSP Infrastructure](https://www.moloco.com/r-d-blog/challenges-in-building-a-scalable-demand-side-platform-dsp-service)
- [Adobe: Behind the Curtain of a High-Performance Bidder](https://experienceleaguecommunities.adobe.com/t5/adobe-advertising-cloud-blogs/behind-the-curtain-of-a-high-performance-bidder-service/ba-p/765771)
- [ONNX Runtime Java API](https://onnxruntime.ai/docs/get-started/with-java.html)
- [RTB ML Pipeline Architecture](https://e-mindset.space/blog/ads-platform-part-2-rtb-ml-pipeline/)
- [RTB Implementation Details](https://e-mindset.space/blog/ads-platform-part-5-implementation/)
