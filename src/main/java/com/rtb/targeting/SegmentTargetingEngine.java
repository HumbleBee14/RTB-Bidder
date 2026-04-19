package com.rtb.targeting;

import com.rtb.model.AdCandidate;
import com.rtb.model.AdContext;
import com.rtb.model.Campaign;
import com.rtb.model.UserProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Matches campaigns to users based on segment overlap.
 * A campaign matches if the user has at least one segment from the campaign's target set.
 */
public final class SegmentTargetingEngine implements TargetingEngine {

    @Override
    public List<AdCandidate> match(List<Campaign> campaigns, UserProfile user, AdContext context) {
        Set<String> userSegments = user.segments();
        if (userSegments.isEmpty()) {
            return List.of();
        }

        List<AdCandidate> candidates = new ArrayList<>();
        for (Campaign campaign : campaigns) {
            if (hasOverlap(campaign.targetSegments(), userSegments)) {
                candidates.add(new AdCandidate(campaign));
            }
        }
        return candidates;
    }

    private boolean hasOverlap(Set<String> targetSegments, Set<String> userSegments) {
        // Check the smaller set against the larger for O(min(m,n)) instead of O(m*n)
        Set<String> smaller = targetSegments.size() <= userSegments.size() ? targetSegments : userSegments;
        Set<String> larger = smaller == targetSegments ? userSegments : targetSegments;
        for (String segment : smaller) {
            if (larger.contains(segment)) {
                return true;
            }
        }
        return false;
    }
}
