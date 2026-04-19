"""
Train an XGBoost pCTR (predicted Click-Through Rate) model and export to ONNX.

Reads feature definitions from feature_schema.json — the single source of truth
shared between this training script and the Java MLScorer.

Usage:
  pip install xgboost scikit-learn skl2onnx onnxmltools numpy
  python ml/train_pctr_model.py
  → outputs ml/pctr_model.onnx
"""

import json
import numpy as np
from pathlib import Path
from xgboost import XGBClassifier
from skl2onnx import convert_sklearn, update_registered_converter
from skl2onnx.common.data_types import FloatTensorType
from skl2onnx.common.shape_calculator import calculate_linear_classifier_output_shapes
from onnxmltools.convert.xgboost.operator_converters.XGBoost import convert_xgboost
from sklearn.model_selection import train_test_split
from sklearn.metrics import roc_auc_score, accuracy_score

SCRIPT_DIR = Path(__file__).parent
SCHEMA_PATH = SCRIPT_DIR / "feature_schema.json"


def load_schema():
    with open(SCHEMA_PATH) as f:
        schema = json.load(f)
    segments = schema["segments"]
    app_categories = schema["app_categories"]
    device_types = schema["device_types"]
    num_features = len(segments) + len(app_categories) + len(device_types) + len(schema["extra_features"])
    return segments, app_categories, device_types, num_features


def generate_synthetic_data(segments, app_categories, device_types, num_features, n_samples=100_000):
    """Generate realistic synthetic click data."""
    np.random.seed(42)

    segment_index = {s: i for i, s in enumerate(segments)}
    app_index = {c: i for i, c in enumerate(app_categories)}
    device_index = {d: i for i, d in enumerate(device_types)}

    n_segments = len(segments)
    n_apps = len(app_categories)
    n_devices = len(device_types)

    X = np.zeros((n_samples, num_features), dtype=np.float32)
    y = np.zeros(n_samples, dtype=np.int32)

    for i in range(n_samples):
        # Random user segments (3-8 per user)
        num_segs = np.random.randint(3, 9)
        seg_indices = np.random.choice(n_segments, num_segs, replace=False)
        X[i, seg_indices] = 1.0

        # Random app category
        app_idx = np.random.randint(n_apps)
        X[i, n_segments + app_idx] = 1.0

        # Random device type
        device_idx = np.random.randint(n_devices)
        X[i, n_segments + n_apps + device_idx] = 1.0

        # Hour of day (normalized 0-1)
        hour = np.random.randint(0, 24)
        X[i, -2] = hour / 23.0

        # Campaign bid floor
        bid_floor = np.random.uniform(0.1, 2.0)
        X[i, -1] = bid_floor

        # Click probability — realistic distributions
        base_ctr = 0.03

        relevance_boost = 0.0
        if X[i, segment_index["sports"]] and app_idx == app_index.get("sports", -1):
            relevance_boost += 0.05
        if X[i, segment_index["tech"]] and app_idx == app_index.get("gaming", -1):
            relevance_boost += 0.04
        if X[i, segment_index["shopping"]] or X[i, segment_index["deal_seeker"]]:
            relevance_boost += 0.02
        if X[i, segment_index["frequent_buyer"]]:
            relevance_boost += 0.03
        if device_idx == device_index["mobile"]:
            relevance_boost += 0.01
        if 18 <= hour <= 22:
            relevance_boost += 0.02
        relevance_boost += bid_floor * 0.01

        click_prob = min(base_ctr + relevance_boost, 0.25)
        y[i] = 1 if np.random.random() < click_prob else 0

    return X, y


def main():
    print(f"Loading feature schema from {SCHEMA_PATH}")
    segments, app_categories, device_types, num_features = load_schema()
    print(f"  {len(segments)} segments, {len(app_categories)} app categories, "
          f"{len(device_types)} device types, {num_features} total features")

    print("Generating synthetic click data...")
    X, y = generate_synthetic_data(segments, app_categories, device_types, num_features)
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
        [("features", FloatTensorType([None, num_features]))],
        target_opset={"": 12, "ai.onnx.ml": 2},
        options={"zipmap": False},
    )

    output_path = SCRIPT_DIR / "pctr_model.onnx"
    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())

    print(f"  Model saved to {output_path}")
    print(f"  Schema: {SCHEMA_PATH}")
    print(f"  Both training and serving read from the same schema — no feature drift")


if __name__ == "__main__":
    main()
