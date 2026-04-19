package com.rtb.model;

/** Reasons for declining to bid (HTTP 204). */
public enum NoBidReason {

    NO_MATCHING_CAMPAIGN,
    ALL_FREQUENCY_CAPPED,
    BUDGET_EXHAUSTED,
    TIMEOUT,
    INTERNAL_ERROR
}
