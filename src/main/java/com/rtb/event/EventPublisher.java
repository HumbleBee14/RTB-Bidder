package com.rtb.event;

import com.rtb.event.events.BidEvent;
import com.rtb.event.events.ClickEvent;
import com.rtb.event.events.ImpressionEvent;
import com.rtb.event.events.WinEvent;

/** Publishes ad lifecycle events. Implementations must be non-blocking and thread-safe. */
public interface EventPublisher {

    void publishBid(BidEvent event);

    void publishWin(WinEvent event);

    void publishImpression(ImpressionEvent event);

    void publishClick(ClickEvent event);
}
