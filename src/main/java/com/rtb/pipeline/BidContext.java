package com.rtb.pipeline;

import com.rtb.model.BidRequest;
import com.rtb.model.BidResponse;
import com.rtb.model.NoBidReason;

/**
 * Mutable context that flows through the pipeline.
 * Each stage reads/writes fields as needed. A single instance per request.
 */
public class BidContext {

    private final BidRequest request;
    private final long startTimeNanos;
    private final long deadlineNanos;

    private BidResponse response;
    private NoBidReason noBidReason;

    public BidContext(BidRequest request, long startTimeNanos, long deadlineNanos) {
        this.request = request;
        this.startTimeNanos = startTimeNanos;
        this.deadlineNanos = deadlineNanos;
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

    public void setResponse(BidResponse response) {
        this.response = response;
    }

    public NoBidReason getNoBidReason() {
        return noBidReason;
    }

    /** Abort the pipeline — no-bid with the given reason. */
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
