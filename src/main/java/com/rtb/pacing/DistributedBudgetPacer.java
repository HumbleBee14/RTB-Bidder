package com.rtb.pacing;

import com.rtb.config.RedisConfig;
import com.rtb.model.Campaign;
import com.rtb.repository.CampaignRepository;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Multi-instance budget pacing using Redis DECRBY for atomic distributed decrements.
 *
 * When running multiple bidder instances behind a load balancer, each instance must
 * see the same budget state. Redis is the shared truth — DECRBY is atomic across instances.
 *
 * Budgets stored as integer microdollars in Redis: budget:{campaignId}
 */
public final class DistributedBudgetPacer implements BudgetPacer, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DistributedBudgetPacer.class);
    private static final long MICRODOLLAR = 1_000_000L;

    // Atomic DECRBY + check: returns remaining, rolls back if negative
    private static final String TRY_SPEND_SCRIPT =
            "local remaining = redis.call('DECRBY', KEYS[1], ARGV[1]) " +
            "if remaining < 0 then " +
            "  redis.call('INCRBY', KEYS[1], ARGV[1]) " +
            "  return -1 " +
            "end " +
            "return remaining";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public DistributedBudgetPacer(RedisConfig config, CampaignRepository campaignRepository) {
        RedisURI uri = RedisURI.create(config.uri());
        uri.setTimeout(Duration.ofMillis(config.connectTimeoutMs()));

        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.commands = connection.sync();
        connection.setTimeout(Duration.ofMillis(config.commandTimeoutMs()));

        // Seed budgets into Redis (only if not already set)
        for (Campaign campaign : campaignRepository.getActiveCampaigns()) {
            String key = "budget:" + campaign.id();
            long budgetMicros = (long) (campaign.budget() * MICRODOLLAR);
            commands.setnx(key, String.valueOf(budgetMicros));
        }
        logger.info("DistributedBudgetPacer connected to Redis: {}:{}", uri.getHost(), uri.getPort());
    }

    @Override
    public boolean trySpend(String campaignId, double amount) {
        String key = "budget:" + campaignId;
        long amountMicros = Math.round(amount * MICRODOLLAR);
        if (amountMicros <= 0) return false;
        Long result = commands.eval(TRY_SPEND_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{key}, String.valueOf(amountMicros));
        return result != null && result >= 0;
    }

    @Override
    public double remainingBudget(String campaignId) {
        String value = commands.get("budget:" + campaignId);
        return value != null ? Long.parseLong(value) / (double) MICRODOLLAR : 0.0;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
