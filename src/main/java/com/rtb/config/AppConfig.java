package com.rtb.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolution order (highest wins):
 *   1. Environment variable (SERVER_PORT style)
 *   2. application.properties
 *   3. Default value
 */
public final class AppConfig {

    private final Properties properties;

    private AppConfig(Properties properties) {
        this.properties = properties;
    }

    public static AppConfig load() {
        Properties props = new Properties();
        try (InputStream is = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }
        return new AppConfig(props);
    }

    /**
     * Get a config value. Checks env var first (dots→underscores, uppercased),
     * then properties file, then returns the default.
     */
    public String get(String key, String defaultValue) {
        String envKey = key.replace(".", "_").replace("-", "_").toUpperCase();
        String envValue = System.getenv(envKey);
        if (envValue != null) {
            return envValue;
        }
        return properties.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    public long getLong(String key, long defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        return Long.parseLong(value.trim());
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
