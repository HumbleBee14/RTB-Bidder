"""
Train an XGBoost pCTR (predicted Click-Through Rate) model and export to ONNX.

Features:
  - user segments (one-hot encoded, 50 segments)
  - app_category (one-hot, 10 categories)
  - device_type (one-hot: mobile, desktop, tablet)
  - hour_of_day (0-23, normalized)
  - campaign_bid_floor (float)

Label: clicked (0 or 1)

Data is synthetic but follows realistic distributions:
  - Base CTR ~2-5% (industry average)
  - Higher CTR for matching segments (sports user + Nike = higher click prob)
  - Mobile > desktop CTR
  - Evening hours > morning CTR

Usage:
  pip install xgboost scikit-learn skl2onnx onnxmltools numpy
  python ml/train_pctr_model.py
  → outputs ml/pctr_model.onnx
"""

import numpy as np
from xgboost import XGBClassifier
from skl2onnx import convert_sklearn, update_registered_converter
from skl2onnx.common.data_types import FloatTensorType
from skl2onnx.common.shape_calculator import calculate_linear_classifier_output_shapes
from onnxmltools.convert.xgboost.operator_converters.XGBoost import convert_xgboost
from sklearn.model_selection import train_test_split
from sklearn.metrics import roc_auc_score, accuracy_score

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

APP_CATEGORIES = ["news", "sports", "gaming", "social", "finance",
                   "health", "education", "shopping", "travel", "entertainment"]

DEVICE_TYPES = ["mobile", "desktop", "tablet"]

NUM_SEGMENT_FEATURES = len(SEGMENTS)      # 50
NUM_APP_FEATURES = len(APP_CATEGORIES)    # 10
NUM_DEVICE_FEATURES = len(DEVICE_TYPES)   # 3
# + hour_of_day (1) + bid_floor (1) = total 65 features
NUM_FEATURES = NUM_SEGMENT_FEATURES + NUM_APP_FEATURES + NUM_DEVICE_FEATURES + 2

SEGMENT_INDEX = {s: i for i, s in enumerate(SEGMENTS)}
APP_INDEX = {c: i for i, c in enumerate(APP_CATEGORIES)}
DEVICE_INDEX = {d: i for i, d in enumerate(DEVICE_TYPES)}


def generate_synthetic_data(n_samples=100_000):
    """Generate realistic synthetic click data."""
    np.random.seed(42)
    X = np.zeros((n_samples, NUM_FEATURES), dtype=np.float32)
    y = np.zeros(n_samples, dtype=np.int32)

    for i in range(n_samples):
        # Random user segments (3-8 segments per user)
        num_segments = np.random.randint(3, 9)
        segment_indices = np.random.choice(NUM_SEGMENT_FEATURES, num_segments, replace=False)
        X[i, segment_indices] = 1.0

        # Random app category
        app_idx = np.random.randint(NUM_APP_FEATURES)
        X[i, NUM_SEGMENT_FEATURES + app_idx] = 1.0

        # Random device type
        device_idx = np.random.randint(NUM_DEVICE_FEATURES)
        X[i, NUM_SEGMENT_FEATURES + NUM_APP_FEATURES + device_idx] = 1.0

        # Hour of day (normalized 0-1)
        hour = np.random.randint(0, 24)
        X[i, -2] = hour / 23.0

        # Campaign bid floor
        bid_floor = np.random.uniform(0.1, 2.0)
        X[i, -1] = bid_floor

        # Click probability — realistic distributions
        base_ctr = 0.03  # 3% base CTR

        # Segment relevance boost (matching segments increase CTR)
        relevance_boost = 0.0
        if X[i, SEGMENT_INDEX["sports"]] and app_idx == APP_INDEX.get("sports", -1):
            relevance_boost += 0.05
        if X[i, SEGMENT_INDEX["tech"]] and app_idx == APP_INDEX.get("gaming", -1):
            relevance_boost += 0.04
        if X[i, SEGMENT_INDEX["shopping"]] or X[i, SEGMENT_INDEX["deal_seeker"]]:
            relevance_boost += 0.02
        if X[i, SEGMENT_INDEX["frequent_buyer"]]:
            relevance_boost += 0.03

        # Device boost (mobile > desktop)
        if device_idx == DEVICE_INDEX["mobile"]:
            relevance_boost += 0.01

        # Time boost (evening hours 18-22 have higher CTR)
        if 18 <= hour <= 22:
            relevance_boost += 0.02

        # Higher bid floor campaigns tend to have better creatives
        relevance_boost += bid_floor * 0.01

        click_prob = min(base_ctr + relevance_boost, 0.25)
        y[i] = 1 if np.random.random() < click_prob else 0

    return X, y


def main():
    print("Generating synthetic click data...")
    X, y = generate_synthetic_data(100_000)
    print(f"  Samples: {len(y)}, Positive rate: {y.mean():.3f}")

    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    print("Training XGBoost model...")
    model = XGBClassifier(
        n_estimators=100,
        max_depth=5,
        learning_rate=0.1,
        eval_metric="logloss",
        random_state=42
    )
    model.fit(X_train, y_train)

    y_pred_proba = model.predict_proba(X_test)[:, 1]
    y_pred = model.predict(X_test)
    print(f"  AUC: {roc_auc_score(y_test, y_pred_proba):.4f}")
    print(f"  Accuracy: {accuracy_score(y_test, y_pred):.4f}")

    # Register XGBoost converter for ONNX export
    update_registered_converter(
        XGBClassifier,
        "XGBoostXGBClassifier",
        calculate_linear_classifier_output_shapes,
        convert_xgboost,
        options={"nocl": [True, False], "zipmap": [True, False]},
    )

    print("Exporting to ONNX...")
    onnx_model = convert_sklearn(
        model,
        "pctr_xgboost",
        [("features", FloatTensorType([None, NUM_FEATURES]))],
        target_opset={"": 12, "ai.onnx.ml": 2},
        options={"zipmap": False},
    )

    output_path = "ml/pctr_model.onnx"
    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())

    print(f"  Model saved to {output_path}")
    print(f"  Features: {NUM_FEATURES}")
    print(f"  Feature order: {NUM_SEGMENT_FEATURES} segments + {NUM_APP_FEATURES} app cats + "
          f"{NUM_DEVICE_FEATURES} device types + hour + bid_floor")

    # Save feature metadata for Java MLScorer
    with open("ml/feature_spec.txt", "w") as f:
        f.write(f"total_features={NUM_FEATURES}\n")
        f.write(f"segments={','.join(SEGMENTS)}\n")
        f.write(f"app_categories={','.join(APP_CATEGORIES)}\n")
        f.write(f"device_types={','.join(DEVICE_TYPES)}\n")


if __name__ == "__main__":
    main()
