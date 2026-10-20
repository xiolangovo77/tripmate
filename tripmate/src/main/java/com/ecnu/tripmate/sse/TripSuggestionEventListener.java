package com.ecnu.tripmate.sse;

import com.ecnu.tripmate.event.TripSuggestionsChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 复用旅行SSE连接，仅在建议变更事务提交成功后广播通知。 */
@Component
public class TripSuggestionEventListener {
    private final TripSseService sseService;

    public TripSuggestionEventListener(TripSseService sseService) {
        this.sseService = sseService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void suggestionsChanged(TripSuggestionsChangedEvent event) {
        sseService.broadcast(event.tripId(), "trip.suggestions.changed");
    }
}
