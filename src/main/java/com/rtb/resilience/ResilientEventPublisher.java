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
 * When Kafka is down:
 *   - Events are silently dropped (logged at debug level)
 *   - The bid path is never affected — events are fire-and-forget
 *   - When Kafka recovers, circuit transitions HALF_OPEN → CLOSED, events flow again
 *
 * WinEvents (billing-critical) are logged at WARN when dropped — these need
 * manual reconciliation if Kafka was down during billing events.
 */
public final class ResilientEventPublisher implements EventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(ResilientEventPublisher.class);

    private final EventPublisher delegate;
    private final CircuitBreaker circuitBreaker;

    public ResilientEventPublisher(EventPublisher delegate, int failureThreshold, long cooldownMs) {
        this.delegate = delegate;
        this.circuitBreaker = new CircuitBreaker("kafka-events", failureThreshold, cooldownMs);
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
                    // WinEvents are billing-critical — warn loudly when dropped
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
