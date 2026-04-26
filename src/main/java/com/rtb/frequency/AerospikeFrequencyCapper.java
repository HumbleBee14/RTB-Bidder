package com.rtb.frequency;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.Value;
import com.aerospike.client.async.EventLoops;
import com.aerospike.client.async.NioEventLoops;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.Operation;
import com.rtb.config.AerospikeConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aerospike-backed frequency capping. Drop-in replacement for RedisFrequencyCapper —
 * implements the same FrequencyCapper interface so the pipeline is unchanged.
 *
 * Storage:
 *   namespace:  rtb (configurable)
 *   set:        freq (configurable)
 *   key:        userId:campaignId
 *   bin:        c (int — impression count)
 *   TTL:        3600s (set on the record itself, Aerospike handles expiry natively)
 *
 * Why Aerospike for freq cap:
 *   Single-record atomic INCR + TTL via the operate() API in one round-trip,
 *   single-digit-µs reads, and built for ad-tech read+write ratios at very
 *   high RPS. At our current ceiling (~10K RPS) Redis is already adequate;
 *   this implementation exists so we can A/B them empirically rather than
 *   guess which to use at higher scales.
 */
public final class AerospikeFrequencyCapper implements FrequencyCapper, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AerospikeFrequencyCapper.class);
    private static final int WINDOW_SECONDS = 3600;
    private static final String COUNT_BIN = "c";

    private final EventLoops eventLoops;
    private final AerospikeClient client;
    private final String namespace;
    private final String set;
    private final Policy readPolicy;
    private final WritePolicy incrPolicy;
    private final Timer getTimer;
    private final Timer batchGetTimer;
    private final Timer incrTimer;

    public AerospikeFrequencyCapper(AerospikeConfig config, MeterRegistry registry) {
        this.namespace = config.namespace();
        this.set = config.set();

        this.eventLoops = new NioEventLoops(config.eventLoopSize());

        ClientPolicy clientPolicy = new ClientPolicy();
        clientPolicy.eventLoops = eventLoops;
        clientPolicy.timeout = config.connectTimeoutMs();
        // Pre-create one connection pool per event loop for parallel decode capacity.
        clientPolicy.maxConnsPerNode = Math.max(64, config.eventLoopSize() * 16);

        this.client = new AerospikeClient(clientPolicy, new Host(config.host(), config.port()));

        this.readPolicy = new Policy(client.getReadPolicyDefault());
        this.readPolicy.totalTimeout = config.commandTimeoutMs();

        this.incrPolicy = new WritePolicy(client.getWritePolicyDefault());
        this.incrPolicy.totalTimeout = config.commandTimeoutMs();
        this.incrPolicy.expiration = WINDOW_SECONDS;  // record-level TTL refreshed on each write

        this.getTimer = Timer.builder("aerospike_client_command_duration_seconds")
                .tag("command", "get")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);
        this.batchGetTimer = Timer.builder("aerospike_client_command_duration_seconds")
                .tag("command", "batchget")
                .description("Batch freq-cap read — one round-trip for all candidates")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);
        this.incrTimer = Timer.builder("aerospike_client_command_duration_seconds")
                .tag("command", "operate")
                .description("Atomic INCR + TTL refresh in one operate() call")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);

        logger.info("FrequencyCapper connected to Aerospike: {}:{} namespace={} set={} eventLoops={}",
                config.host(), config.port(), namespace, set, config.eventLoopSize());
    }

    private Key key(String userId, String campaignId) {
        return new Key(namespace, set, userId + ":" + campaignId);
    }

    @Override
    public boolean isAllowed(String userId, String campaignId, int maxImpressions) {
        Key k = key(userId, campaignId);
        return getTimer.record(() -> {
            try {
                Record rec = client.get(readPolicy, k, COUNT_BIN);
                long count = rec != null ? rec.getLong(COUNT_BIN) : 0;
                return count < maxImpressions;
            } catch (AerospikeException e) {
                logger.warn("GET freq counter failed for {}/{}: {}", userId, campaignId, e.getMessage());
                return true;  // fail-open
            }
        });
    }

    @Override
    public Set<String> allowedCampaignIds(String userId, Map<String, Integer> campaignMaxImpressions) {
        if (campaignMaxImpressions.isEmpty()) {
            return Set.of();
        }

        List<String> campaignIds = new ArrayList<>(campaignMaxImpressions.keySet());
        Key[] keys = new Key[campaignIds.size()];
        for (int i = 0; i < campaignIds.size(); i++) {
            keys[i] = key(userId, campaignIds.get(i));
        }

        Record[] results = batchGetTimer.record(() -> {
            try {
                return client.get(client.getBatchPolicyDefault(), keys, COUNT_BIN);
            } catch (AerospikeException e) {
                logger.warn("Batch GET freq counters failed for user {} ({} keys): {}",
                        userId, keys.length, e.getMessage());
                return null;
            }
        });

        if (results == null) {
            return new HashSet<>(campaignIds);  // fail-open
        }

        Set<String> allowed = new HashSet<>();
        for (int i = 0; i < campaignIds.size(); i++) {
            String campaignId = campaignIds.get(i);
            Record rec = results[i];
            long count = rec != null ? rec.getLong(COUNT_BIN) : 0;
            if (count < campaignMaxImpressions.get(campaignId)) {
                allowed.add(campaignId);
            }
        }
        return allowed;
    }

    @Override
    public void recordImpression(String userId, String campaignId) {
        Key k = key(userId, campaignId);
        // Note: just Operation.add — no touch(). On first INCR the record doesn't exist,
        // and touch() on a non-existent key fails with "Key not found". The WritePolicy's
        // expiration already refreshes TTL on every write, so touch is redundant anyway.
        incrTimer.record(() -> {
            try {
                client.operate(incrPolicy, k, Operation.add(new Bin(COUNT_BIN, Value.get(1L))));
            } catch (AerospikeException e) {
                logger.warn("INCR freq counter failed for {}/{}: {}", userId, campaignId, e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        client.close();
        eventLoops.close();
    }
}
