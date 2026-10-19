package com.ecnu.tripmate.sse;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.service.TripMemberService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 旅行SSE服务，负责单实例连接管理、心跳和变更通知推送。
 * 客户端收到通知后，通过对应业务的HTTP接口重新读取数据。
 */
@Service
@Slf4j
public class TripSseService {
    private final TripMemberService members;
    private final ConcurrentMap<SseEmitter, TripSseConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("trip-members-heartbeat").factory());
    // 使用有界线程池隔离慢连接，避免阻塞业务事务或无限堆积发送任务。
    private final ExecutorService sender = new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().daemon().name("trip-members-send-", 0).factory());

    /**
     * 初始化旅行SSE服务。
     *
     * @param members 旅行成员服务，用于校验访问权限
     */
    public TripSseService(TripMemberService members) {
        this.members = members;
    }

    /**
     * 服务初始化后启动心跳任务，每20秒检查连接权限并发送心跳。
     */
    @PostConstruct
    void start() {
        heartbeat.scheduleWithFixedDelay(() -> connections.values().forEach(c -> enqueue(c, null)),
                20, 20, TimeUnit.SECONDS);
    }

    /**
     * 校验成员权限并注册SSE连接，同时安排首次成员同步通知。
     *
     * @param tripId 旅行ID
     * @param userId 当前登录用户ID
     * @return 有效期为2分钟的SSE连接，重连时会重新校验登录状态
     */
    public SseEmitter subscribe(long tripId, long userId) {
        members.checkMemberAccess(tripId, userId);
        // 即使页面没有操作，也会定期重连并通过控制器重新校验登录状态。
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(2));
        TripSseConnection connection = new TripSseConnection(tripId, userId, emitter);
        connections.put(emitter, connection);
        emitter.onCompletion(() -> connections.remove(emitter));
        emitter.onTimeout(() -> close(connection));
        emitter.onError(error -> close(connection));
        // 注册后再次校验，处理订阅建立期间成员退出的情况。
        enqueue(connection, "trip.members.changed");
        return emitter;
    }

    /**
     * 向该旅行的所有本机订阅连接广播数据变更通知。
     * 相同事件会合并，适用于提示客户端重新查询，不用于逐条传输业务消息。
     *
     * @param tripId 旅行ID
     * @param eventName SSE事件名称，由业务事件监听器指定
     */
    public void broadcast(long tripId, String eventName) {
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("SSE事件名称不能为空");
        }
        connections.values().stream().filter(c -> c.tripId == tripId)
                .forEach(c -> enqueue(c, eventName));
    }

    /**
     * 将连接的发送任务加入队列，并按事件名称合并尚未发送的变更通知。
     *
     * @param connection 目标订阅连接
     * @param eventName 事件名称；为null时仅安排心跳或处理已有变更
     */
    private void enqueue(TripSseConnection connection, String eventName) {
        if (!connections.containsKey(connection.emitter)) return;
        if (eventName != null) connection.pendingEvents.add(eventName);
        if (!connection.queued.compareAndSet(false, true)) return;
        try {
            sender.execute(() -> send(connection));
        } catch (RejectedExecutionException rejected) {
            connection.queued.set(false);
            close(connection); // 客户端重连后会重新查询成员列表。
        }
    }

    /**
     * 复核成员权限后发送变更通知或心跳，权限失效或发送失败时清理连接。
     *
     * @param connection 目标订阅连接
     */
    private void send(TripSseConnection connection) {
        try {
            if (!connections.containsKey(connection.emitter)) return;
            members.checkMemberAccess(connection.tripId, connection.userId);
            String[] eventNames = connection.pendingEvents.toArray(String[]::new);
            for (String eventName : eventNames) {
                // 发送前移除，发送期间收到的同名变更会保留到下一轮。
                connection.pendingEvents.remove(eventName);
                connection.emitter.send(SseEmitter.event().name(eventName)
                        .reconnectTime(3000)
                        .data(Map.of("tripId", Long.toString(connection.tripId))));
            }
            if (eventNames.length == 0) {
                connection.emitter.send(SseEmitter.event().comment("heartbeat"));
            }
        } catch (BusinessException denied) {
            try {
                connection.emitter.send(SseEmitter.event().name("trip.access.revoked")
                        .data(Map.of("tripId", Long.toString(connection.tripId))));
            } catch (IOException | IllegalStateException ignored) {
                // 已断开的客户端在下方统一清理。
            } finally {
                close(connection);
            }
        } catch (IOException | IllegalStateException disconnected) {
            close(connection);
        } catch (RuntimeException failure) {
            log.warn("SSE stream failed for trip {}", connection.tripId, failure);
            close(connection);
        } finally {
            connection.queued.set(false);
            // 发送期间发生的新变更需要再次通知，避免遗漏。
            if (!connection.pendingEvents.isEmpty()) enqueue(connection, null);
        }
    }

    /**
     * 移除并结束订阅连接，重复调用不会重复结束连接。
     *
     * @param connection 待清理的订阅连接
     */
    private void close(TripSseConnection connection) {
        if (connections.remove(connection.emitter) != null) connection.emitter.complete();
    }

    /**
     * 服务销毁时停止心跳、关闭全部订阅连接并关闭发送线程池。
     */
    @PreDestroy
    void stop() {
        heartbeat.shutdownNow();
        connections.values().forEach(this::close);
        sender.shutdownNow();
    }

}
