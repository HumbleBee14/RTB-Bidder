package com.rtb.pipeline;

import com.rtb.model.BidRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Object pool for BidContext — acquire/release instead of new/GC.
 *
 * At 50K QPS, creating a new BidContext per request = 50K objects/sec for GC to collect.
 * With pooling: objects are reused, GC sees near-zero allocation from the bid path.
 *
 * Thread-safe: ConcurrentLinkedQueue is lock-free.
 * Pool grows on demand but never shrinks (objects return to pool after use).
 */
public final class BidContextPool {

    private static final Logger logger = LoggerFactory.getLogger(BidContextPool.class);

    private final ConcurrentLinkedQueue<BidContext> pool = new ConcurrentLinkedQueue<>();
    private final int maxSize;
    private final AtomicInteger created = new AtomicInteger(0);

    public BidContextPool(int maxSize) {
        this.maxSize = maxSize;
    }

    public BidContext acquire(BidRequest request, long startTimeNanos, long deadlineNanos) {
        BidContext ctx = pool.poll();
        if (ctx != null) {
            ctx.reset(request, startTimeNanos, deadlineNanos);
            return ctx;
        }
        created.incrementAndGet();
        return new BidContext(request, startTimeNanos, deadlineNanos);
    }

    public void release(BidContext ctx) {
        if (pool.size() < maxSize) {
            ctx.clear();
            pool.offer(ctx);
        }
        // If pool is full, object is abandoned to GC
    }

    public int poolSize() { return pool.size(); }
    public int totalCreated() { return created.get(); }
}
