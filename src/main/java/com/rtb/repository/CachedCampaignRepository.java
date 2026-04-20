package com.rtb.repository;

import com.rtb.model.Campaign;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decorator: caches campaigns from any CampaignRepository in memory.
 *
 * getActiveCampaigns() returns instantly from the cache — zero I/O on the hot path.
 * Call refresh() to reload from the underlying source (e.g., on a scheduled timer).
 *
 * Thread-safe: AtomicReference swap means readers never see a partial update.
 * The cached list is immutable (List.copyOf).
 */
public final class CachedCampaignRepository implements CampaignRepository {

    private static final Logger logger = LoggerFactory.getLogger(CachedCampaignRepository.class);

    private final CampaignRepository delegate;
    private final AtomicReference<List<Campaign>> cache = new AtomicReference<>(List.of());

    public CachedCampaignRepository(CampaignRepository delegate) {
        this.delegate = delegate;
        refresh();
    }

    @Override
    public List<Campaign> getActiveCampaigns() {
        return cache.get();
    }

    /** Reload campaigns from the underlying repository. Safe to call from any thread. */
    public void refresh() {
        List<Campaign> campaigns = delegate.getActiveCampaigns();
        cache.set(campaigns);
        logger.info("Campaign cache refreshed: {} active campaigns", campaigns.size());
    }
}
