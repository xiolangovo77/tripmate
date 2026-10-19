package com.ecnu.tripmate.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个旅行订阅连接及其发送调度状态，仅供SSE包内部使用。
 */
final class TripSseConnection {
    final long tripId;
    final long userId;
    final SseEmitter emitter;
    final AtomicBoolean queued = new AtomicBoolean();
    final Set<String> pendingEvents = ConcurrentHashMap.newKeySet();

    /**
     * 初始化旅行订阅连接。
     *
     * @param tripId 旅行ID
     * @param userId 订阅用户ID
     * @param emitter SSE事件发送器
     */
    TripSseConnection(long tripId, long userId, SseEmitter emitter) {
        this.tripId = tripId;
        this.userId = userId;
        this.emitter = emitter;
    }
}
