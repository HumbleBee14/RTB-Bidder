package com.rtb.server;

import com.rtb.frequency.FrequencyCapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded write-behind queue for post-response frequency-cap updates.
 *
 * Why this exists:
 *   Serving the bid response is the latency-critical path. Recording the winning
 *   campaign's impression is important, but it can happen a few milliseconds later.
 *   A bounded queue prevents the event loop from creating unbounded background work
 *   during bursts, which otherwise pollutes the next load window with leftover Redis
 *   writes.
 */
public final class ImpressionRecorder implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ImpressionRecorder.class);

    private final BlockingQueue<ImpressionWrite> queue;
    private final ExecutorService workers;
    private final FrequencyCapper frequencyCapper;
    private final Counter droppedWrites;

    public ImpressionRecorder(FrequencyCapper frequencyCapper,
                              int workerCount,
                              int queueCapacity,
                              MeterRegistry registry) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("postresponse.impression.workers must be >= 1, got: " + workerCount);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("postresponse.impression.queueCapacity must be >= 1, got: " + queueCapacity);
        }

        this.frequencyCapper = frequencyCapper;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.droppedWrites = Counter.builder("postresponse_impression_dropped_total")
                .description("Impression writes dropped because the bounded write-behind queue was full")
                .register(registry);

        registry.gauge("postresponse_impression_queue_size", queue, BlockingQueue::size);
        registry.gauge("postresponse_impression_queue_remaining", queue, BlockingQueue::remainingCapacity);

        this.workers = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread t = new Thread(runnable, "impression-recorder");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::drainLoop);
        }

        logger.info("ImpressionRecorder started: workers={}, queueCapacity={}", workerCount, queueCapacity);
    }

    public void record(String userId, String campaignId) {
        if (!queue.offer(new ImpressionWrite(userId, campaignId))) {
            droppedWrites.increment();
            logger.debug("Impression queue full — dropped write for user={} campaign={}", userId, campaignId);
        }
    }

    private void drainLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ImpressionWrite write = queue.take();
                frequencyCapper.recordImpression(write.userId(), write.campaignId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    private record ImpressionWrite(String userId, String campaignId) {}
}
