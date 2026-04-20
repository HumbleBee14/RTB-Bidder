package com.rtb.event;

import com.rtb.event.events.BidEvent;
import com.rtb.event.events.ClickEvent;
import com.rtb.event.events.ImpressionEvent;
import com.rtb.event.events.WinEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs events instead of publishing to Kafka. For development without Kafka running. */
public final class NoOpEventPublisher implements EventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(NoOpEventPublisher.class);

    @Override
    public void publishBid(BidEvent event) {
        logger.debug("BidEvent: requestId={}, bid={}, slots={}", event.requestId(), event.bid(), event.slotBids().size());
    }

    @Override
    public void publishWin(WinEvent event) {
        logger.debug("WinEvent: bidId={}, clearingPrice={}", event.bidId(), event.clearingPrice());
    }

    @Override
    public void publishImpression(ImpressionEvent event) {
        logger.debug("ImpressionEvent: bidId={}, campaignId={}", event.bidId(), event.campaignId());
    }

    @Override
    public void publishClick(ClickEvent event) {
        logger.debug("ClickEvent: bidId={}, campaignId={}", event.bidId(), event.campaignId());
    }
}
