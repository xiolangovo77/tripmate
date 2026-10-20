package com.ecnu.tripmate.sse;

import com.ecnu.tripmate.event.TripItineraryChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 旅行行程业务事件与SSE推送之间的适配层。
 */
@Component
public class TripItineraryEventListener {
    private final TripSseService sseService;

    /**
     * 初始化旅行行程事件监听器。
     *
     * @param sseService 旅行SSE连接与推送服务
     */
    public TripItineraryEventListener(TripSseService sseService) {
        this.sseService = sseService;
    }

    /**
     * 行程变更事务提交成功后，通知该旅行的所有本机订阅连接。
     *
     * @param event 旅行行程变更事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void itineraryChanged(TripItineraryChangedEvent event) {
        sseService.broadcast(event.tripId(), "trip.itinerary.changed");
    }
}
