package com.rtb.model;

import java.util.Set;

/** User profile with audience segments fetched from Redis. */
public record UserProfile(String userId, Set<String> segments) {

    public boolean hasSegment(String segment) {
        return segments.contains(segment);
    }
}
