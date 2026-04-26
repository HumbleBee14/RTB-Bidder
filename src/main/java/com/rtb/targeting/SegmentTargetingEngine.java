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
 *
 * Implementation: encodes segment names as bit positions (see SegmentBitmap), so the
 * per-campaign overlap check is a single bitwise AND on two longs. The user's bitmap
 * is computed once per request from the freshly-fetched Set<String>; campaign target
 * bitmaps are lazily cached forever (or until catalog reload).
 */
public final class SegmentTargetingEngine implements TargetingEngine {

    @Override
    public List<AdCandidate> match(List<Campaign> campaigns, UserProfile user, AdContext context) {
        Set<String> userSegments = user.segments();
        if (userSegments.isEmpty()) {
            return List.of();
        }

        long userBits = SegmentBitmap.encode(userSegments);   // one-shot per request

        List<AdCandidate> candidates = new ArrayList<>();
        for (Campaign campaign : campaigns) {
            long targetBits = SegmentBitmap.forCampaign(campaign);   // cached across requests
            if ((userBits & targetBits) != 0L) {
                candidates.add(new AdCandidate(campaign));
            }
        }
        return candidates;
    }
}
