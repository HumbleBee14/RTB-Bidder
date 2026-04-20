package com.rtb.resilience;

import com.rtb.event.EventPublisher;
import com.rtb.event.events.BidEvent;
import com.rtb.event.events.ClickEvent;
import com.rtb.event.events.ImpressionEvent;
import com.rtb.event.events.WinEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps EventPublisher with circuit breaker.
 *
 * Kafka's async callback reports failures via recordExternalFailure() —
 * the circuit breaker detects failures even though KafkaEventPublisher
 * catches exceptions internally (fire-and-forget pattern).
 */
public final class ResilientEventPublisher implements EventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(ResilientEventPublisher.class);

    private final EventPublisher delegate;
    private final CircuitBreaker circuitBreaker;

    public ResilientEventPublisher(EventPublisher delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void publishBid(BidEvent event) {
        circuitBreaker.execute(
                () -> { delegate.publishBid(event); },
                () -> { logger.debug("Kafka circuit open — dropping BidEvent {}", event.requestId()); }
        );
    }

    @Override
    public void publishWin(WinEvent event) {
        circuitBreaker.execute(
                () -> { delegate.publishWin(event); },
                () -> {
                    logger.warn("Kafka circuit open — DROPPED WinEvent bidId={} clearingPrice={}",
                            event.bidId(), event.clearingPrice());
                }
        );
    }

    @Override
    public void publishImpression(ImpressionEvent event) {
        circuitBreaker.execute(
                () -> { delegate.publishImpression(event); },
                () -> { logger.debug("Kafka circuit open — dropping ImpressionEvent {}", event.bidId()); }
        );
    }

    @Override
    public void publishClick(ClickEvent event) {
        circuitBreaker.execute(
                () -> { delegate.publishClick(event); },
                () -> { logger.debug("Kafka circuit open — dropping ClickEvent {}", event.bidId()); }
        );
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
