package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.cache.quota.ActivityRedis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuotaDispatchBatchTest {
    @Test
    void mixedOutcomesInOneBatchOnlyClearRoutedAck() {
        var redis = mock(StringRedisTemplate.class);
        var template = mock(RabbitTemplate.class);
        var operations = mock(RabbitOperations.class);
        var dispatcher = new QuotaStreamDispatcher(null, redis, template, new Queue("grants"), new ObjectMapper());
        String prefix = ActivityRedis.prefix(8);
        List<MapRecord<String,String,String>> entries = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            var fields = Map.of("activityId", "8", "grantAmount", "5", "userId", Integer.toString(i), "claimNo", "claim-" + i);
            entries.add(MapRecord.create(prefix + "outbox", fields).withId(RecordId.of(i + "-0")));
        }

        //1、所有消息发出后逆序给出结果，覆盖成功、退回后 ACK、NACK 和确认超时。
        doAnswer(call -> call.<RabbitOperations.OperationsCallback<?>>getArgument(0).doInRabbit(operations))
                .when(template).invoke(any());
        List<CorrelationData> confirmations = new ArrayList<>();
        doAnswer(call -> {
            confirmations.add(call.getArgument(3));
            if (confirmations.size() == 4) {
                confirmations.get(3).getFuture().completeExceptionally(new TimeoutException());
                confirmations.get(2).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
                confirmations.get(1).setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "", "grants"));
                confirmations.get(1).getFuture().complete(new CorrelationData.Confirm(true, null));
                confirmations.get(0).getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(operations).send(eq(""), eq("grants"), any(Message.class), any(CorrelationData.class));

        //2、只把已路由的成功单号交给清理脚本，其他三条保留以供接管。
        assertThrows(IllegalStateException.class, () -> dispatcher.deliverBatch(8, entries));
        verify(redis).execute(ActivityRedis.CONFIRM, List.of(prefix + "outbox", prefix + "pending"), "1-0", "1", "claim-1");
        verifyNoMoreInteractions(redis);
    }
}
