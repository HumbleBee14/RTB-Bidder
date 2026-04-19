package com.rtb.pipeline;

/** A single step in the bid processing pipeline. */
public interface PipelineStage {

    void process(BidContext ctx) throws PipelineException;

    String name();
}
