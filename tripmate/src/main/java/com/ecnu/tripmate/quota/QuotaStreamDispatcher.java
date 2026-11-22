package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.quota.model.QuotaGrantMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
     * 每次读取新消息或分页检查待确认消息的最大条数。
     */
    private static final int BATCH = 16;
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
    private final String consumerName = UUID.randomUUID().toString();
    private final Map<Long, String> pendingCursors = new HashMap<>();
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

    void dispatchActivity(long activityId) throws Exception {
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
            for (var message : streams.claim(stream, GROUP, consumerName, MIN_IDLE, stale.toArray(RecordId[]::new))) {
                deliver(activityId, message);
            }
        }
        //2、读取从未分配的新消息；即使进程退出，消息仍留在该消费组。
        var messages = streams.read(Consumer.from(GROUP, consumerName), StreamReadOptions.empty().count(BATCH),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (messages != null) {
            for (var message : messages) {
                deliver(activityId, message);
            }
        }
    }

    private void deliver(long activityId, MapRecord<String, String, String> entry) throws Exception {
        var fields = entry.getValue();
        var grant = new QuotaGrantMessage(fields.get("claimNo"), Long.parseLong(fields.get("activityId")),
                Long.parseLong(fields.get("userId")), Long.parseLong(fields.get("grantAmount")));
        if (grant.getActivityId() != activityId) throw new IllegalStateException("活动与 Stream 不符");
        //1、业务单号保持不变；每次发送使用独立的 confirm 关联 ID。
        var message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(grant)).setContentType("application/json")
                .setMessageId(grant.getClaimNo()).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send("", queue.getName(), message, correlation);
        var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ 未确认投递到目标队列：" + grant.getClaimNo());
        }
        //2、在同一 Redis 调用中确认并移除待投递消息，永久领取标记继续用于查询。
        String prefix = ActivityRedis.prefix(activityId);
        stringRedisTemplate.execute(ActivityRedis.CONFIRM, List.of(entry.getStream(), prefix + "pending"),
                entry.getId().getValue(), fields.get("userId"), grant.getClaimNo());
    }
}
