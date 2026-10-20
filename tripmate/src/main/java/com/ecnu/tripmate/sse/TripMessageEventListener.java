package com.ecnu.tripmate.sse;

import com.ecnu.tripmate.event.TripMessagesChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TripMessageEventListener {
    private final TripSseService sseService;

    public TripMessageEventListener(TripSseService sseService) {
        this.sseService = sseService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void messagesChanged(TripMessagesChangedEvent event) {
        sseService.broadcast(event.tripId(), "trip.messages.changed");
    }
}
