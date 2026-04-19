package com.rtb.pipeline;

/**
 * A single step in the bid processing pipeline.
 * Implementations must be stateless and thread-safe — a single instance is shared across all requests.
 */
public interface PipelineStage {

    void process(BidContext ctx);

    String name();
}
