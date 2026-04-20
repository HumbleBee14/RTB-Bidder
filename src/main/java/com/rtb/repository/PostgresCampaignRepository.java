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
 *
 * Connection lifecycle: creates a fresh connection per getActiveCampaigns() call and closes
 * it immediately. This method runs at startup and optionally on a periodic refresh timer —
 * not on the hot path. A long-lived connection would go stale between infrequent refreshes
 * (isClosed() returns false but the socket is broken), causing silent failures.
 */
public final class PostgresCampaignRepository implements CampaignRepository {

    private static final Logger logger = LoggerFactory.getLogger(PostgresCampaignRepository.class);

    private static final String LOAD_ACTIVE_SQL =
            "SELECT id, advertiser, budget, bid_floor, target_segments, creative_sizes, " +
            "creative_url, advertiser_domain, max_impressions_per_hour, value_per_click " +
            "FROM campaigns WHERE active = TRUE";

    private final String jdbcUrl;
    private final Properties connectionProps;

    public PostgresCampaignRepository(PostgresConfig config) {
        this.jdbcUrl = config.jdbcUrl();
        this.connectionProps = new Properties();
        this.connectionProps.setProperty("user", config.user());
        this.connectionProps.setProperty("password", config.password());

        // Verify connectivity at startup — fail fast if PostgreSQL is unreachable
        try (Connection conn = DriverManager.getConnection(jdbcUrl, connectionProps)) {
            logger.info("Connected to PostgreSQL: {}:{}/{}", config.host(), config.port(), config.database());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to PostgreSQL: " + jdbcUrl, e);
        }
    }

    @Override
    public List<Campaign> getActiveCampaigns() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, connectionProps);
             PreparedStatement stmt = conn.prepareStatement(LOAD_ACTIVE_SQL);
             ResultSet rs = stmt.executeQuery()) {

            List<Campaign> campaigns = new ArrayList<>();
            while (rs.next()) {
                campaigns.add(mapRow(rs));
            }
            logger.info("Loaded {} active campaigns from PostgreSQL", campaigns.size());
            return List.copyOf(campaigns);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load campaigns from PostgreSQL", e);
        }
    }

    private Campaign mapRow(ResultSet rs) throws SQLException {
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
        try {
            String[] values = (String[]) sqlArray.getArray();
            // Set.of() throws on duplicates — use LinkedHashSet for safety
            return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
        } finally {
            sqlArray.free();
        }
    }
}
