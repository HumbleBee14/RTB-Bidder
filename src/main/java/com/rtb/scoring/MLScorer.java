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
 * Score = pCTR × valuePerClick
 *
 * Feature vector is defined by feature_schema.json — shared with the Python training script.
 * Thread-safety: synchronized on OrtSession (not thread-safe for run()).
 */
public final class MLScorer implements Scorer, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MLScorer.class);

    private final OrtEnvironment env;
    private final OrtSession session;
    private final FeatureSchema schema;

    public MLScorer(String modelPath, FeatureSchema schema) {
        this.schema = schema;
        try {
            this.env = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                this.session = env.createSession(modelPath, opts);
            }
            logger.info("Loaded ONNX model: {} (features: {})", modelPath, schema.numFeatures());
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
            long[] shape = {1, schema.numFeatures()};
            try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape)) {
                synchronized (session) {
                    try (var results = session.run(Map.of("features", input))) {
                        float[][] probs = (float[][]) results.get(1).getValue();
                        return probs[0][1];
                    }
                }
            }
        } catch (OrtException e) {
            logger.error("ONNX inference failed, returning 0", e);
            return 0.0f;
        }
    }

    private float[] buildFeatureVector(Campaign campaign, UserProfile user, AdContext context) {
        float[] features = new float[schema.numFeatures()];
        List<String> segments = schema.segments();
        List<String> appCategories = schema.appCategories();
        List<String> deviceTypes = schema.deviceTypes();

        // Segment features (one-hot)
        Set<String> userSegments = user.segments();
        for (int i = 0; i < segments.size(); i++) {
            if (userSegments.contains(segments.get(i))) {
                features[i] = 1.0f;
            }
        }

        // App category (one-hot)
        int appOffset = segments.size();
        if (context.appCategory() != null) {
            int appIdx = appCategories.indexOf(context.appCategory());
            if (appIdx >= 0) {
                features[appOffset + appIdx] = 1.0f;
            }
        }

        // Device type (one-hot)
        int deviceOffset = appOffset + appCategories.size();
        if (context.deviceType() != null) {
            int deviceIdx = deviceTypes.indexOf(context.deviceType());
            if (deviceIdx >= 0) {
                features[deviceOffset + deviceIdx] = 1.0f;
            }
        }

        // Extra features — positions derived from schema
        int extraOffset = schema.extraFeaturesOffset();
        List<String> extras = schema.extraFeatures();
        for (int i = 0; i < extras.size(); i++) {
            features[extraOffset + i] = switch (extras.get(i)) {
                case "hour_of_day" -> LocalTime.now().getHour() / 23.0f;
                case "bid_floor" -> (float) campaign.bidFloor();
                default -> 0.0f;
            };
        }

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
