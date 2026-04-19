package com.rtb.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads feature schema from feature_schema.json — single source of truth
 * shared between Python training and Java serving.
 *
 * Ref: IAB Content Taxonomy https://iabtechlab.com/standards/content-taxonomy/
 */
public final class FeatureSchema {

    private static final Logger logger = LoggerFactory.getLogger(FeatureSchema.class);

    private final List<String> segments;
    private final List<String> appCategories;
    private final List<String> deviceTypes;
    private final int numFeatures;

    private FeatureSchema(List<String> segments, List<String> appCategories, List<String> deviceTypes) {
        this.segments = List.copyOf(segments);
        this.appCategories = List.copyOf(appCategories);
        this.deviceTypes = List.copyOf(deviceTypes);
        // segments + app_categories + device_types + hour_of_day + bid_floor
        this.numFeatures = segments.size() + appCategories.size() + deviceTypes.size() + 2;
    }

    /** Load from filesystem path (alongside ONNX model). */
    @SuppressWarnings("unchecked")
    public static FeatureSchema load(String schemaPath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> schema;

            // Try filesystem first, then classpath
            Path path = Path.of(schemaPath);
            if (Files.exists(path)) {
                schema = mapper.readValue(path.toFile(), Map.class);
            } else {
                InputStream is = FeatureSchema.class.getClassLoader().getResourceAsStream(schemaPath);
                if (is == null) {
                    throw new IllegalArgumentException("Feature schema not found: " + schemaPath);
                }
                schema = mapper.readValue(is, Map.class);
            }

            List<String> segments = (List<String>) schema.get("segments");
            List<String> appCategories = (List<String>) schema.get("app_categories");
            List<String> deviceTypes = (List<String>) schema.get("device_types");

            FeatureSchema fs = new FeatureSchema(segments, appCategories, deviceTypes);
            logger.info("Loaded feature schema: {} segments, {} app categories, {} device types, {} total features",
                    fs.segments.size(), fs.appCategories.size(), fs.deviceTypes.size(), fs.numFeatures);
            return fs;

        } catch (IOException e) {
            throw new RuntimeException("Failed to load feature schema: " + schemaPath, e);
        }
    }

    public List<String> segments() { return segments; }
    public List<String> appCategories() { return appCategories; }
    public List<String> deviceTypes() { return deviceTypes; }
    public int numFeatures() { return numFeatures; }
}
