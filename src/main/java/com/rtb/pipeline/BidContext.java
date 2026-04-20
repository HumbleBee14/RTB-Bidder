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
 * Supports object pooling — reset() reinitializes for a new request, clear() prepares for return to pool.
 */
public class BidContext {

    private BidRequest request;
    private long startTimeNanos;
    private long deadlineNanos;

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

    /** Reinitialize for a new request (called by pool on acquire). */
    public void reset(BidRequest request, long startTimeNanos, long deadlineNanos) {
        this.request = request;
        this.startTimeNanos = startTimeNanos;
        this.deadlineNanos = deadlineNanos;
        this.userProfile = null;
        this.candidates = null;
        this.slotWinners.clear();
        this.response = null;
        this.noBidReason = null;
    }

    /** Release references for GC (called by pool on release). */
    public void clear() {
        this.request = null;
        this.userProfile = null;
        this.candidates = null;
        this.slotWinners.clear();
        this.response = null;
        this.noBidReason = null;
    }

    public BidRequest getRequest() { return request; }
    public long getStartTimeNanos() { return startTimeNanos; }
    public long getDeadlineNanos() { return deadlineNanos; }
    public BidResponse getResponse() { return response; }
    public UserProfile getUserProfile() { return userProfile; }
    public void setUserProfile(UserProfile userProfile) { this.userProfile = userProfile; }
    public List<AdCandidate> getCandidates() { return candidates; }
    public void setCandidates(List<AdCandidate> candidates) { this.candidates = candidates; }
    public Map<BidRequest.AdSlot, AdCandidate> getSlotWinners() { return slotWinners; }
    public void setSlotWinner(BidRequest.AdSlot slot, AdCandidate winner) { this.slotWinners.put(slot, winner); }
    public void setResponse(BidResponse response) { this.response = response; }
    public NoBidReason getNoBidReason() { return noBidReason; }
    public void abort(NoBidReason reason) { this.noBidReason = reason; }
    public boolean isAborted() { return noBidReason != null; }
    public long remainingNanos() { return deadlineNanos - System.nanoTime(); }
}
