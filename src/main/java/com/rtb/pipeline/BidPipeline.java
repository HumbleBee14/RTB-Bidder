package com.rtb.pipeline;

import com.rtb.config.PipelineConfig;
import com.rtb.metrics.BidMetrics;
import com.rtb.model.BidRequest;
import com.rtb.model.NoBidReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Orchestrates stages in order. Enforces SLA deadline — aborts with TIMEOUT if exceeded. */
public final class BidPipeline {

    private static final Logger logger = LoggerFactory.getLogger(BidPipeline.class);

    private final List<PipelineStage> stages;
    private final long slaBudgetNanos;
    private final BidMetrics metrics;
    private final BidContextPool contextPool;

    public BidPipeline(List<PipelineStage> stages, PipelineConfig config, BidMetrics metrics) {
        this.stages = List.copyOf(stages);
        this.slaBudgetNanos = TimeUnit.MILLISECONDS.toNanos(config.maxLatencyMs());
        this.metrics = metrics;
        this.contextPool = new BidContextPool(256);
    }

    public BidContext execute(BidRequest request, long startNanos) {
        BidContext ctx = contextPool.acquire(request, startNanos, startNanos + slaBudgetNanos);
        StringBuilder timing = logger.isInfoEnabled() ? new StringBuilder() : null;

        for (PipelineStage stage : stages) {
            if (ctx.isAborted()) {
                break;
            }

            if (ctx.remainingNanos() <= 0) {
                ctx.abort(NoBidReason.TIMEOUT);
                logger.warn("SLA timeout before stage: {}", stage.name());
                break;
            }

            long stageStart = System.nanoTime();
            try {
                stage.process(ctx);
            } catch (PipelineException e) {
                ctx.abort(NoBidReason.INTERNAL_ERROR);
                logger.error("Stage failed: {} — {}", stage.name(), e.getMessage(), e);
                break;
            }

            long stageElapsedNanos = System.nanoTime() - stageStart;
            metrics.recordStageLatency(stage.name(), stageElapsedNanos);

            if (timing != null) {
                long elapsedMicros = stageElapsedNanos / 1_000;
                if (timing.length() > 0) timing.append(", ");
                timing.append(stage.name()).append(": ").append(elapsedMicros / 1000)
                        .append('.').append(String.valueOf(elapsedMicros % 1000 + 1000).substring(1))
                        .append("ms");
            }
        }

        // Post-loop SLA check — catches last stage exceeding deadline
        if (!ctx.isAborted() && ctx.remainingNanos() <= 0) {
            ctx.abort(NoBidReason.TIMEOUT);
            logger.warn("SLA timeout after pipeline completed");
        }

        // Null response guard — pipeline ran but no stage set a response
        if (!ctx.isAborted() && ctx.getResponse() == null) {
            ctx.abort(NoBidReason.INTERNAL_ERROR);
            logger.error("Pipeline completed but no response was set");
        }

        if (timing != null) {
            long totalMicros = (System.nanoTime() - startNanos) / 1_000;
            logger.info("Pipeline: [{}] total={}ms deadline={}ms bid={}",
                    timing,
                    totalMicros / 1000 + "." + String.valueOf(totalMicros % 1000 + 1000).substring(1),
                    TimeUnit.NANOSECONDS.toMillis(this.slaBudgetNanos),
                    !ctx.isAborted());
        }

        return ctx;
    }

    /** Return context to pool after handler is done reading it. */
    public void release(BidContext ctx) {
        contextPool.release(ctx);
    }
}
