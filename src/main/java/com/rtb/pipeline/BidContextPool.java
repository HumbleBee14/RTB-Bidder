package com.rtb.pipeline;

import com.rtb.model.BidRequest;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Object pool for BidContext — acquire/release instead of new/GC.
 *
 * Thread-safe: ConcurrentLinkedQueue (lock-free) + AtomicInteger for O(1) size tracking.
 * Pool grows on demand, capped at maxSize. After warmup, zero allocations.
 */
public final class BidContextPool {

    private final ConcurrentLinkedQueue<BidContext> pool = new ConcurrentLinkedQueue<>();
    private final int maxSize;
    private final AtomicInteger currentSize = new AtomicInteger(0);
    private final AtomicInteger totalCreated = new AtomicInteger(0);

    public BidContextPool(int maxSize) {
        this.maxSize = maxSize;
    }

    public BidContext acquire(BidRequest request, long startTimeNanos, long deadlineNanos) {
        BidContext ctx = pool.poll();
        if (ctx != null) {
            currentSize.decrementAndGet();
            ctx.reset(request, startTimeNanos, deadlineNanos);
            return ctx;
        }
        totalCreated.incrementAndGet();
        return new BidContext(request, startTimeNanos, deadlineNanos);
    }

    public void release(BidContext ctx) {
        // AtomicInteger is O(1), unlike ConcurrentLinkedQueue.size() which is O(n)
        if (currentSize.get() < maxSize) {
            ctx.clear();
            pool.offer(ctx);
            currentSize.incrementAndGet();
        }
    }

    public int poolSize() { return currentSize.get(); }
    public int totalCreated() { return totalCreated.get(); }
}
