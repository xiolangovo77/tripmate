package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.quota.model.QuotaGrantMessage;
import com.ecnu.tripmate.service.QuotaGrantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class QuotaGrantConsumer implements ChannelAwareMessageListener {
    /** 校验并提交单笔领取的入账事务。 */
    private final QuotaGrantService quotaGrantService;
    /** 解析 Redis 预扣时产生的 JSON 快照。 */
    private final ObjectMapper objectMapper;
    /** 投递死信时核对路由及发布确认。 */
    private final RabbitTemplate rabbitTemplate;
    /** 接收永久失败和重试耗尽消息的持久化队列名称。 */
    private final String deadQueueName;

    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        //1、每次重试通过独立服务开启新事务，非法消息直接送死信。
        try {
            QuotaGrantMessage grant = decode(message);
            grantWithRetry(grant);
        } catch (InterruptedException interrupted) {
            channel.basicNack(tag, false, true);
            Thread.currentThread().interrupt();
            return;
        } catch (Exception failure) {
            park(message, channel, failure);
            return;
        }
        //2、事务代理正常返回后才执行 ACK，测试可在此处阻塞消费进程。
        acknowledgeCommitted(message, channel);
    }

    void acknowledgeCommitted(Message message, Channel channel) throws IOException {
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private void grantWithRetry(QuotaGrantMessage grant) throws InterruptedException {
        //1、每次调用事务代理独立完成一次入账，快照冲突直接交给死信处理。
        for (int attempt = 1; ; attempt++) {
            try {
                quotaGrantService.grant(grant);
                return;
            } catch (IllegalArgumentException conflict) {
                throw conflict;
            } catch (RuntimeException error) {
                //2、其余入账异常按 1、2、4 秒退避，第四次失败后保留消息供核对。
                if (attempt >= 4) throw error;
                long delaySeconds = 1L << (attempt - 1);
                log.warn("额度入账第 {} 次失败，{} 秒后重试，claimNo={}", attempt, delaySeconds, grant.getClaimNo(), error);
                TimeUnit.SECONDS.sleep(delaySeconds);
            }
        }
    }

    private QuotaGrantMessage decode(Message message) throws IOException {
        //1、校验消息正文中的活动、用户和额度。
        JsonNode body = objectMapper.readTree(message.getBody());
        if (body == null || !body.isObject()) throw new IllegalArgumentException("领取消息正文无效");
        String number = body.path("claimNo").asText("");
        long activity = positiveLong(body, "activityId");
        long user = positiveLong(body, "userId");
        long amount = positiveLong(body, "grantAmount");
        //2、核对消息 ID 与原领取单号，保证重投沿用同一业务标识。
        if (!number.equals(message.getMessageProperties().getMessageId()) || !number.startsWith(activity + "-")
                || !number.matches("[1-9][0-9]{0,18}-[0-9a-f]{32}") || amount > 4294967295L) {
            throw new IllegalArgumentException("领取单号、消息 ID 或发放额度无效");
        }
        return new QuotaGrantMessage(number, activity, user, amount);
    }

    private long positiveLong(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("领取消息字段无效：" + field);
        }
        return value.longValue();
    }

    private void park(Message message, Channel channel, Exception failure) throws Exception {
        //1、保留原消息正文和业务单号，附上失败原因供核对与重放。
        String number = message.getMessageProperties().getMessageId();
        log.error("额度入账失败，转入死信队列，claimNo={}", number, failure);
        Message dead = MessageBuilder.fromClonedMessage(message)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("quota-failure", failure.getClass().getSimpleName() + ": " + failure.getMessage()).build();
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            rabbitTemplate.send("", deadQueueName, dead, correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IOException("死信投递未确认");
            }
        } catch (Exception unavailable) {
            log.error("死信转存失败，保留原消息，claimNo={}", number, unavailable);
            try {
                TimeUnit.SECONDS.sleep(1);
            } finally {
                channel.basicNack(tag, false, true);
            }
            return;
        }
        //2、死信已可靠转存后才从原队列移除这次投递。
        channel.basicAck(tag, false);
    }
}
