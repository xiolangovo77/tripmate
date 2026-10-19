package com.ecnu.tripmate.sse;

import com.ecnu.tripmate.event.TripMembersChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 旅行成员业务事件与SSE推送之间的适配层。
 */
@Component
public class TripMemberEventListener {
    private final TripSseService sseService;

    /**
     * 初始化旅行成员事件监听器。
     *
     * @param sseService 旅行SSE连接与推送服务
     */
    public TripMemberEventListener(TripSseService sseService) {
        this.sseService = sseService;
    }

    /**
     * 成员变更事务提交成功后，通知该旅行的所有本机订阅连接。
     *
     * @param event 旅行成员变更事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void membersChanged(TripMembersChangedEvent event) {
        sseService.broadcast(event.tripId(), "trip.members.changed");
    }
}
