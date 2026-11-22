package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.cache.quota.ActivityRedis;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.quota.model.CreateActivityRequest;
import com.ecnu.tripmate.service.QuotaActivityService;
import com.ecnu.tripmate.service.QuotaClaimService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 计时范围为读取首批 Stream 至全部待办清空；预扣准备和队列逐条对账在计时外。 */
@EnabledIfSystemProperty(named = "quota.dispatch.benchmark", matches = "true")
class QuotaDispatchBenchmarkIT {
    @Test
    void drainTenThousand() throws Exception {
        String tag = "dispatch-it-" + UUID.randomUUID();
        try (var context = SpringApplication.run(QuotaFlashSaleIT.Config.class, "--spring.profiles.active=dev",
                "--server.port=0", "--server.address=127.0.0.1", "--quota.consumer.enabled=false",
                "--quota.dispatch.enabled=false", "--quota.dispatch.queue=" + tag, "--logging.level.root=WARN")) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            var admin = context.getBean(AmqpAdmin.class);
            var queue = context.getBean(Queue.class);
            try {
                admin.declareQueue(queue);
                var accounts = IntStream.range(0, 10000).mapToObj(i -> tag + "-" + i).toList();
                jdbc.batchUpdate("INSERT INTO `user`(userAccount,password,nickname,memberExpireAt) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 1 DAY))",
                        accounts, 500, (ps, account) -> {
                            ps.setString(1, account);
                            ps.setString(2, tag);
                            ps.setString(3, tag);
                        });
                var users = jdbc.queryForList("SELECT id FROM `user` WHERE nickname=? ORDER BY id", Long.class, tag);
                assertEquals(10000, users.size());
                measure(context, jdbc, users.subList(0, 100), 0, tag);
                for (int round = 1; round <= 3; round++) measure(context, jdbc, users, round, tag);
            } finally {
                admin.deleteQueue(tag);
                jdbc.update("DELETE FROM `user` WHERE nickname=?", tag);
            }
        }
    }

    private void measure(ConfigurableApplicationContext context, JdbcTemplate jdbc, List<Long> users, int round,
                         String queueName) throws Exception {
        var activities = context.getBean(QuotaActivityService.class);
        var claims = context.getBean(QuotaClaimService.class);
        var redis = context.getBean(StringRedisTemplate.class);
        var rabbit = context.getBean(RabbitTemplate.class);
        var json = context.getBean(ObjectMapper.class);
        var queue = context.getBean(Queue.class);
        var dispatcher = new QuotaStreamDispatcher(context.getBean(QuotaActivityMapper.class), redis, rabbit, queue, json);
        var draft = new CreateActivityRequest();
        draft.setName(queueName);
        draft.setRuleText("可靠投递对比测试");
        draft.setTotalStock((long) users.size());
        draft.setGrantAmount(5L);
        draft.setStartTime(LocalDateTime.now().minusHours(1));
        draft.setEndTime(LocalDateTime.now().plusHours(1));
        long id = activities.create(draft);
        String prefix = ActivityRedis.prefix(id);
        String stream = prefix + "outbox";
        try {
            //1、每个有效会员走生产预扣服务生成一笔待办，保存用户与原单号以便逐笔核对。
            activities.publish(id);
            Map<String, Long> expected = new HashMap<>();
            try (var pool = Executors.newFixedThreadPool(12)) {
                List<Callable<Map.Entry<String,Long>>> tasks = users.stream().<Callable<Map.Entry<String,Long>>>map(
                        user -> () -> Map.entry(claims.claim(id, user).getClaimNo(), user)).toList();
                for (var result : pool.invokeAll(tasks)) {
                    var entry = result.get();
                    assertNull(expected.put(entry.getKey(), entry.getValue()));
                }
            }
            assertEquals(0L, activities.detail(id).getRemainingStock());
            assertEquals((long) users.size(), redis.opsForStream().size(stream));
            assertEquals(users.size() + 1L, redis.opsForHash().size(prefix + "pending"));
            //2、持续调用同一活动的生产投递入口，排除定时调度空等时间。
            long start = System.nanoTime();
            do {
                dispatcher.dispatchActivity(id);
                assertTrue(System.nanoTime() - start < TimeUnit.MINUTES.toNanos(8), "投递超时");
            } while (redis.opsForStream().size(stream) != 0L);
            long elapsed = System.nanoTime() - start;
            assertEquals(0L, redis.opsForStream().pending(stream, "quota-dispatch").getTotalPendingMessages());
            assertEquals(1L, redis.opsForHash().size(prefix + "pending"));
            //3、独占队列收到的持久化消息逐条匹配原始单号、用户、活动与发放次数。
            rabbit.execute(channel -> {
                assertEquals(users.size(), channel.queueDeclarePassive(queueName).getMessageCount());
                for (int i = 0; i < users.size(); i++) {
                    var received = channel.basicGet(queueName, true);
                    assertNotNull(received);
                    var body = json.readTree(received.getBody());
                    String number = body.path("claimNo").asText();
                    assertEquals(number, received.getProps().getMessageId());
                    assertEquals(2, received.getProps().getDeliveryMode());
                    assertEquals(id, body.path("activityId").asLong());
                    assertEquals(5, body.path("grantAmount").asInt());
                    assertEquals(Long.valueOf(body.path("userId").asLong()), expected.remove(number));
                }
                return null;
            });
            assertTrue(expected.isEmpty());
            System.out.printf(Locale.ROOT, "DISPATCH_VERIFIED round=%d entries=%d elapsedMs=%.2f rate=%.2f missing=0 duplicates=0 pending=0%n",
                    round, users.size(), elapsed / 1e6, users.size() * 1e9 / elapsed);
        } finally {
            redis.delete(List.of("stock","rules","reservations","pending","outbox","config","config-version").stream()
                    .map(suffix -> prefix + suffix).toList());
            jdbc.update("DELETE FROM quota_activity WHERE id=? AND name=?", id, queueName);
        }
    }
}
