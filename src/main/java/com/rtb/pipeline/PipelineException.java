package com.rtb.pipeline;

/** Unchecked exception thrown by pipeline stages. Caught by BidPipeline, triggers no-bid. */
public class PipelineException extends RuntimeException {

    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
