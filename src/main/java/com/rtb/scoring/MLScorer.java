package com.rtb.scoring;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ML-based scorer using ONNX Runtime for pCTR prediction.
 * Score = pCTR × valuePerClick × pacingFactor
 *
 * Feature vector (65 floats):
 *   [0-49]  user segments (one-hot)
 *   [50-59] app category (one-hot)
 *   [60-62] device type (one-hot)
 *   [63]    hour of day (normalized 0-1)
 *   [64]    campaign bid floor
 *
 * Thread-safety: OrtSession.run() is NOT thread-safe. We synchronize on the session.
 * For higher throughput, use a session pool (one per thread).
 */
public final class MLScorer implements Scorer, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MLScorer.class);

    private static final List<String> SEGMENTS = List.of(
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
    );

    private static final List<String> APP_CATEGORIES = List.of(
            "news", "sports", "gaming", "social", "finance",
            "health", "education", "shopping", "travel", "entertainment"
    );

    private static final List<String> DEVICE_TYPES = List.of("mobile", "desktop", "tablet");

    private static final int NUM_FEATURES = SEGMENTS.size() + APP_CATEGORIES.size() + DEVICE_TYPES.size() + 2;

    private final OrtEnvironment env;
    private final OrtSession session;

    public MLScorer(String modelPath) {
        try {
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = env.createSession(modelPath, opts);
            logger.info("Loaded ONNX model: {} (features: {})", modelPath, NUM_FEATURES);
        } catch (OrtException e) {
            throw new RuntimeException("Failed to load ONNX model: " + modelPath, e);
        }
    }

    @Override
    public double score(Campaign campaign, UserProfile user, AdContext context) {
        float[] features = buildFeatureVector(campaign, user, context);
        float pCTR = predict(features);
        return pCTR * campaign.valuePerClick();
    }

    private float predict(float[] features) {
        try {
            long[] shape = {1, NUM_FEATURES};
            OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape);
            // Output index 1 = probabilities [batch, n_classes], class 1 = click probability
            synchronized (session) {
                try (var results = session.run(Map.of("features", input))) {
                    float[][] probs = (float[][]) results.get(1).getValue();
                    return probs[0][1]; // P(click)
                }
            }
        } catch (OrtException e) {
            logger.error("ONNX inference failed, returning 0", e);
            return 0.0f;
        }
    }

    private float[] buildFeatureVector(Campaign campaign, UserProfile user, AdContext context) {
        float[] features = new float[NUM_FEATURES];

        // Segment features (one-hot)
        Set<String> userSegments = user.segments();
        for (int i = 0; i < SEGMENTS.size(); i++) {
            if (userSegments.contains(SEGMENTS.get(i))) {
                features[i] = 1.0f;
            }
        }

        // App category (one-hot)
        if (context.appCategory() != null) {
            int appIdx = APP_CATEGORIES.indexOf(context.appCategory());
            if (appIdx >= 0) {
                features[SEGMENTS.size() + appIdx] = 1.0f;
            }
        }

        // Device type (one-hot)
        if (context.deviceType() != null) {
            int deviceIdx = DEVICE_TYPES.indexOf(context.deviceType());
            if (deviceIdx >= 0) {
                features[SEGMENTS.size() + APP_CATEGORIES.size() + deviceIdx] = 1.0f;
            }
        }

        // Hour of day (normalized)
        features[NUM_FEATURES - 2] = LocalTime.now().getHour() / 23.0f;

        // Campaign bid floor
        features[NUM_FEATURES - 1] = (float) campaign.bidFloor();

        return features;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            logger.warn("Failed to close ONNX session", e);
        }
    }
}
