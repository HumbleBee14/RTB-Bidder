package com.rtb.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtb.model.Campaign;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Loads campaigns from a JSON file on classpath. Used when PostgreSQL is not configured. */
public final class JsonCampaignRepository implements CampaignRepository {

    private static final Logger logger = LoggerFactory.getLogger(JsonCampaignRepository.class);

    private final List<Campaign> campaigns;

    public JsonCampaignRepository(ObjectMapper objectMapper, String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Campaign file not found: " + resourcePath);
            }
            this.campaigns = List.copyOf(objectMapper.readValue(is, new TypeReference<List<Campaign>>() {}));
            logger.info("Loaded {} campaigns from {}", campaigns.size(), resourcePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load campaigns from " + resourcePath, e);
        }
    }

    @Override
    public List<Campaign> getActiveCampaigns() {
        return campaigns;
    }
}
