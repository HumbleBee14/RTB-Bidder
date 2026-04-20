package com.rtb.repository;

import com.rtb.config.PostgresConfig;
import com.rtb.model.Campaign;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * Loads campaigns from PostgreSQL via JDBC.
 *
 * Why JDBC and not Vert.x reactive PG client:
 * Campaigns are loaded at startup (or on a timer for refresh), never on the event loop.
 * JDBC is simpler, blocks only the calling thread, and the CachedCampaignRepository
 * decorator ensures getActiveCampaigns() returns instantly from memory on the hot path.
 * Using a reactive client for a startup-time bulk load would add complexity with no benefit.
 */
public final class PostgresCampaignRepository implements CampaignRepository, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PostgresCampaignRepository.class);

    private static final String LOAD_ACTIVE_SQL =
            "SELECT id, advertiser, budget, bid_floor, target_segments, creative_sizes, " +
            "creative_url, advertiser_domain, max_impressions_per_hour, value_per_click " +
            "FROM campaigns WHERE active = TRUE";

    private final PostgresConfig config;
    private Connection connection;

    public PostgresCampaignRepository(PostgresConfig config) {
        this.config = config;
        this.connection = connect();
    }

    private Connection connect() {
        try {
            logger.info("Connecting to PostgreSQL: {} user={}", config.jdbcUrl(), config.user());
            Properties props = new Properties();
            props.setProperty("user", config.user());
            props.setProperty("password", config.password());
            Connection conn = DriverManager.getConnection(config.jdbcUrl(), props);
            logger.info("Connected to PostgreSQL: {}:{}/{}", config.host(), config.port(), config.database());
            return conn;
        } catch (SQLException e) {
            logger.error("PostgreSQL connection failed: {} — state={}", e.getMessage(), e.getSQLState());
            throw new RuntimeException("Failed to connect to PostgreSQL: " + config.jdbcUrl(), e);
        }
    }

    @Override
    public List<Campaign> getActiveCampaigns() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = connect();
            }
            try (PreparedStatement stmt = connection.prepareStatement(LOAD_ACTIVE_SQL);
                 ResultSet rs = stmt.executeQuery()) {

                List<Campaign> campaigns = new ArrayList<>();
                while (rs.next()) {
                    campaigns.add(mapRow(rs));
                }
                logger.info("Loaded {} active campaigns from PostgreSQL", campaigns.size());
                return List.copyOf(campaigns);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load campaigns from PostgreSQL", e);
        }
    }

    private Campaign mapRow(ResultSet rs) throws SQLException {
        // PostgreSQL TEXT[] → Java Set<String>
        Array segmentsArray = rs.getArray("target_segments");
        Array sizesArray = rs.getArray("creative_sizes");

        Set<String> segments = arrayToSet(segmentsArray);
        Set<String> sizes = arrayToSet(sizesArray);

        return new Campaign(
                rs.getString("id"),
                rs.getString("advertiser"),
                rs.getDouble("budget"),
                rs.getDouble("bid_floor"),
                segments,
                sizes,
                rs.getString("creative_url"),
                rs.getString("advertiser_domain"),
                rs.getInt("max_impressions_per_hour"),
                rs.getDouble("value_per_click")
        );
    }

    private Set<String> arrayToSet(Array sqlArray) throws SQLException {
        if (sqlArray == null) return Set.of();
        String[] values = (String[]) sqlArray.getArray();
        return Set.of(values);
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("PostgreSQL connection closed");
            } catch (SQLException e) {
                logger.warn("Failed to close PostgreSQL connection: {}", e.getMessage());
            }
        }
    }
}
