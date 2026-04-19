package com.rtb.repository;

import java.util.Set;

/** Fetches user audience segments. Implementations must be thread-safe. */
public interface UserSegmentRepository {

    Set<String> getSegments(String userId);
}
