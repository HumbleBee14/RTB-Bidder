package com.rtb.pipeline;

import com.rtb.model.AdCandidate;
import com.rtb.model.BidRequest;
import com.rtb.model.BidResponse;
import com.rtb.model.NoBidReason;
import com.rtb.model.UserProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable context that flows through the pipeline.
 * Each stage reads/writes fields as needed. A single instance per request.
 */
public class BidContext {

    private final BidRequest request;
    private final long startTimeNanos;
    private final long deadlineNanos;

    private UserProfile userProfile;
    private List<AdCandidate> candidates;
    private Map<BidRequest.AdSlot, AdCandidate> slotWinners;
    private BidResponse response;
    private NoBidReason noBidReason;

    public BidContext(BidRequest request, long startTimeNanos, long deadlineNanos) {
        this.request = request;
        this.startTimeNanos = startTimeNanos;
        this.deadlineNanos = deadlineNanos;
        this.slotWinners = new LinkedHashMap<>();
    }

    public BidRequest getRequest() {
        return request;
    }

    public long getStartTimeNanos() {
        return startTimeNanos;
    }

    public long getDeadlineNanos() {
        return deadlineNanos;
    }

    public BidResponse getResponse() {
        return response;
    }

    public UserProfile getUserProfile() {
        return userProfile;
    }

    public void setUserProfile(UserProfile userProfile) {
        this.userProfile = userProfile;
    }

    public List<AdCandidate> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<AdCandidate> candidates) {
        this.candidates = candidates;
    }

    public Map<BidRequest.AdSlot, AdCandidate> getSlotWinners() {
        return slotWinners;
    }

    public void setSlotWinner(BidRequest.AdSlot slot, AdCandidate winner) {
        this.slotWinners.put(slot, winner);
    }

    public void setResponse(BidResponse response) {
        this.response = response;
    }

    public NoBidReason getNoBidReason() {
        return noBidReason;
    }

    public void abort(NoBidReason reason) {
        this.noBidReason = reason;
    }

    public boolean isAborted() {
        return noBidReason != null;
    }

    public long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }
}
