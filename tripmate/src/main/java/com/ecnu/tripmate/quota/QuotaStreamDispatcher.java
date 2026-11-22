package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.cache.quota.ActivityRedis;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.quota.model.QuotaGrantMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "quota.dispatch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class QuotaStreamDispatcher {
    /**
     * 各投递实例共用的 Redis Stream 消费组名称。
     */
    private static final String GROUP = "quota-dispatch";
    /**
     * 实例同一时间只发送一批，该值同时限制每批读取条数和在途确认数。
     */
    private static final int BATCH = 100;
    /** 单次扫描给一个活动连续发送新消息的时间预算，单位为纳秒，在批次之间检查。 */
    private static final long ACTIVITY_SLICE_NANOS = TimeUnit.SECONDS.toNanos(2);
    /**
     * 待确认消息可被其他投递实例接管前的最短空闲时间，单位为秒。
     */
    private static final Duration MIN_IDLE = Duration.ofSeconds(30);
    /** 按主键分页发现活动，保留对已结束活动的投递机会。 */
    private final QuotaActivityMapper quotaActivityMapper;
    /** 读取 Stream 并在投递确认后清理待办。 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 发送 mandatory 消息并接收 broker 确认。 */
    private final RabbitTemplate rabbitTemplate;
    /** 持久化发放队列，使用默认交换机按队列全名精确路由。 */
    private final Queue queue;
    /** 将领取快照编码为 JSON。 */
    private final ObjectMapper objectMapper;
    /** 实例在 Stream 消费组中的身份。 */
    private final String consumerName = UUID.randomUUID().toString();
    /** 每个活动上次扫描到的待确认记录 ID。 */
    private final Map<Long, String> pendingCursors = new HashMap<>();
    /** 已扫描的最后一个活动 ID，遍历结束后归零。 */
    private long activityCursor;

    @Scheduled(fixedDelayString = "${quota.dispatch.interval-ms:1000}")
    public void dispatch() {
        //1、按活动 ID 分页读取已发布活动，逐个处理待投递消息。
        var ids = quotaActivityMapper.publishedIdsAfter(activityCursor);
        for (long id : ids) {
            try {
                dispatchActivity(id);
            } catch (Exception e) {
                log.warn("额度消息待重投，activityId={}", id, e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        //2、记住下一批的起点，到末页后从头扫描。
        activityCursor = ids.size() == 64 ? ids.get(ids.size() - 1) : 0;
    }

    synchronized void dispatchActivity(long activityId) throws Exception {
        String stream = ActivityRedis.prefix(activityId) + "outbox";
        var streams = stringRedisTemplate.<String, String>opsForStream();
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stream))) return;
        //1、分页检查 PEL，接管投递实例退出后空闲超过阈值的待办。
        String cursor = pendingCursors.get(activityId);
        Range<String> range = cursor == null ? Range.unbounded() : Range.rightUnbounded(Range.Bound.exclusive(cursor));
        var pending = streams.pending(stream, GROUP, range, BATCH);
        List<RecordId> stale = new ArrayList<>();
        for (PendingMessage item : pending) {
            if (item.getElapsedTimeSinceLastDelivery().compareTo(MIN_IDLE) >= 0) stale.add(item.getId());
            pendingCursors.put(activityId, item.getId().getValue());
        }
        if (pending.size() < BATCH) pendingCursors.remove(activityId);
        if (!stale.isEmpty()) {
            deliverBatch(activityId, streams.claim(stream, GROUP, consumerName, MIN_IDLE, stale.toArray(RecordId[]::new)));
        }
        //2、读取从未分配的新消息；即使进程退出，消息仍留在该消费组。
        long start = System.nanoTime();
        do {
            var messages = streams.read(Consumer.from(GROUP, consumerName), StreamReadOptions.empty().count(BATCH),
                    StreamOffset.create(stream, ReadOffset.lastConsumed()));
            if (messages == null || messages.isEmpty()) return;
            deliverBatch(activityId, messages);
            if (messages.size() < BATCH) return;
        } while (System.nanoTime() - start < ACTIVITY_SLICE_NANOS);
    }

    void deliverBatch(long activityId, List<MapRecord<String, String, String>> entries) {
        if (entries.isEmpty()) return;
        //1、在同一发布信道连续发送一批独立的持久化消息，每笔异步判定 confirm 和 return。
        Map<MapRecord<String, String, String>, CompletableFuture<Boolean>> results = new LinkedHashMap<>();
        rabbitTemplate.invoke(operations -> {
            for (var entry : entries) {
                try {
                    results.put(entry, publish(operations, activityId, entry));
                } catch (Exception error) {
                    log.warn("额度消息发送失败，保留待办，streamId={}", entry.getId(), error);
                }
            }
            //2、只在批次边界等待。超时或信道异常时关闭本批信道，未确认记录交由后续接管。
            var confirms = results.values().toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(confirms).exceptionally(failure -> null).join();
            if (results.values().stream().anyMatch(CompletableFuture::isCompletedExceptionally)) {
                try {
                    operations.execute(channel -> {
                        channel.abort();
                        return null;
                    });
                } catch (org.springframework.amqp.AmqpException closed) {
                    log.warn("投递信道关闭异常，已确认结果仍逐条核对，activityId={}", activityId, closed);
                }
            }
            return null;
        });
        //3、一次 Lua 调用清理本批已成功路由的条目，失败记录继续保留在 PEL。
        List<String> confirmed = new ArrayList<>();
        results.forEach((entry, result) -> {
            if (!result.isCompletedExceptionally() && result.join()) {
                confirmed.add(entry.getId().getValue());
                confirmed.add(entry.getValue().get("userId"));
                confirmed.add(entry.getValue().get("claimNo"));
            }
        });
        if (!confirmed.isEmpty()) {
            String prefix = ActivityRedis.prefix(activityId);
            stringRedisTemplate.execute(ActivityRedis.CONFIRM, List.of(prefix + "outbox", prefix + "pending"),
                    confirmed.toArray());
        }
        if (confirmed.size() / 3 != entries.size()) {
            throw new IllegalStateException("部分额度消息未确认路由，activityId=" + activityId);
        }
    }

    private CompletableFuture<Boolean> publish(RabbitOperations operations, long activityId,
            MapRecord<String, String, String> entry) throws JsonProcessingException {
        var fields = entry.getValue();
        var grant = new QuotaGrantMessage(fields.get("claimNo"), Long.parseLong(fields.get("activityId")),
                Long.parseLong(fields.get("userId")), Long.parseLong(fields.get("grantAmount")));
        if (grant.getActivityId() != activityId) throw new IllegalStateException("活动与 Stream 不符");
        //1、业务单号保持不变；每次发送使用独立的 confirm 关联 ID。
        var message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(grant)).setContentType("application/json")
                .setMessageId(grant.getClaimNo()).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        operations.send("", queue.getName(), message, correlation);
        return correlation.getFuture().thenApply(confirm -> confirm.isAck() && correlation.getReturned() == null)
                .orTimeout(5, TimeUnit.SECONDS);
    }
}
