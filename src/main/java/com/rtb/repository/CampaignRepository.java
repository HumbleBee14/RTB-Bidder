package com.rtb.repository;

import com.rtb.model.Campaign;

import java.util.List;

/** Provides active campaigns. Implementations must be thread-safe. */
public interface CampaignRepository {

    List<Campaign> getActiveCampaigns();
}
