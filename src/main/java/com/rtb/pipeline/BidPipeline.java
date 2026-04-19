package com.rtb.pipeline;

import com.rtb.config.PipelineConfig;
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
    private final long deadlineNanos;

    public BidPipeline(List<PipelineStage> stages, PipelineConfig config) {
        this.stages = List.copyOf(stages);
        this.deadlineNanos = TimeUnit.MILLISECONDS.toNanos(config.maxLatencyMs());
    }

    public BidContext execute(BidRequest request, long startNanos) {
        BidContext ctx = new BidContext(request, startNanos, startNanos + deadlineNanos);
        StringBuilder timing = new StringBuilder();

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
                logger.error("Stage failed: {} — {}", stage.name(), e.getMessage());
                break;
            }

            double elapsedMs = (System.nanoTime() - stageStart) / 1_000_000.0;
            if (timing.length() > 0) timing.append(", ");
            timing.append(stage.name()).append(": ").append(String.format("%.2f", elapsedMs)).append("ms");
        }

        double totalMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        logger.info("Pipeline: [{}] total={}ms deadline={}ms bid={}",
                timing,
                String.format("%.2f", totalMs),
                TimeUnit.NANOSECONDS.toMillis(this.deadlineNanos),
                !ctx.isAborted());

        return ctx;
    }
}
