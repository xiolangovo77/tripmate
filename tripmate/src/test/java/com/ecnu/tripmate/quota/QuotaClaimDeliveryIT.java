package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.constant.UserConstant;
import com.ecnu.tripmate.controller.QuotaClaimController;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.quota.model.CreateActivityRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named = "quota.dev.it", matches = "true")
class QuotaClaimDeliveryIT {
    @TestConfiguration(proxyBeanMethods = false)
    @Import({QuotaBenchmarkNode.NodeConfig.class, QuotaClaimController.class, QuotaClaimService.class, QuotaDispatchConfig.class})
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    static class Config {}

    @Test
    void pausedDeliveryCanResumeAndOnlyOwnerCanQuery() throws Exception {
        String tag = "quota-it-" + UUID.randomUUID();
        String otherAccount = tag + "-other";
        try (var context = SpringApplication.run(Config.class, "--spring.profiles.active=dev", "--server.port=0",
                "--server.address=127.0.0.1", "--quota.dispatch.enabled=false", "--quota.dispatch.queue=" + tag,
                "--logging.level.root=WARN", "--spring.main.banner-mode=off")) {
            var service = context.getBean(QuotaActivityService.class);
            var claims = context.getBean(QuotaClaimService.class);
            var controller = context.getBean(QuotaClaimController.class);
            var redis = context.getBean(StringRedisTemplate.class);
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            var admin = context.getBean(AmqpAdmin.class);
            var rabbit = context.getBean(RabbitTemplate.class);
            var queue = context.getBean(Queue.class);
            var json = context.getBean(ObjectMapper.class);
            var dispatcher = new QuotaStreamDispatcher(context.getBean(QuotaActivityMapper.class), redis, rabbit, queue, json);
            var draft = new CreateActivityRequest();
            draft.setName(tag);
            draft.setRuleText("测试活动");
            draft.setStartTime(LocalDateTime.now().minusDays(1));
            draft.setEndTime(LocalDateTime.now().plusDays(1));
            draft.setTotalStock(1L);
            draft.setGrantAmount(5L);
            long id = service.create(draft);
            try {
                admin.declareQueue(queue);
                service.publish(id);
                jdbc.update("INSERT INTO `user`(userAccount,password,nickname,memberExpireAt) VALUES(?,?,?,NULL)",
                        tag, UUID.randomUUID().toString(), tag);
                long owner = jdbc.queryForObject("SELECT id FROM `user` WHERE userAccount=?", Long.class, tag);
                assertThrows(BusinessException.class, () -> claims.claim(id, owner));
                jdbc.update("INSERT INTO `user`(userAccount,password,nickname,memberExpireAt) VALUES(?,?,?,DATE_ADD(NOW(), INTERVAL 1 DAY))",
                        otherAccount, UUID.randomUUID().toString(), tag);
                long other = jdbc.queryForObject("SELECT id FROM `user` WHERE userAccount=?", Long.class, otherAccount);
                jdbc.update("UPDATE `user` SET memberExpireAt=DATE_ADD(NOW(), INTERVAL 1 DAY) WHERE id=? AND userAccount=?", owner, tag);
                var session = new org.springframework.mock.web.MockHttpSession();
                session.setAttribute(UserConstant.USER_LOGIN_STATE, User.builder().id(owner).build());
                var mvc = MockMvcBuilders.standaloneSetup(controller).build();
                String url = "/quota/activities/" + id + "/claims";
                try (var pool = Executors.newFixedThreadPool(20)) {
                    var retries = IntStream.range(0, 100).<Callable<String>>mapToObj(i -> () -> {
                        var response = mvc.perform(post(url).session(session)).andReturn().getResponse();
                        var body = json.readTree(response.getContentAsByteArray());
                        assertEquals(0, body.get("code").asInt());
                        assertEquals("PROCESSING", body.at("/data/status").asText());
                        return body.at("/data/claimNo").asText();
                    }).toList();
                    var results = pool.invokeAll(retries);
                    String number = results.get(0).get();
                    for (var result : results) assertEquals(number, result.get());
                    assertEquals(0L, service.detail(id).getRemainingStock());
                    String stream = ActivityRedis.prefix(id) + "outbox";
                    assertEquals(1L, redis.opsForStream().size(stream));
                    assertEquals(0, admin.getQueueInfo(tag).getMessageCount());
                    var body = mvc.perform(get(url + "/me").session(session)).andReturn().getResponse().getContentAsByteArray();
                    assertEquals(number, json.readTree(body).at("/data/claimNo").asText());
                    assertThrows(BusinessException.class, () -> claims.find(id, other));
                    assertEquals("活动已售罄", assertThrows(BusinessException.class, () -> claims.claim(id, other)).getMessage());
                    // 临时移除本测试专属队列，broker 返回不可路由时必须留下 PEL。
                    admin.deleteQueue(tag);
                    assertThrows(IllegalStateException.class, () -> dispatcher.dispatchActivity(id));
                    assertEquals(1L, redis.opsForStream().size(stream));
                    assertEquals(1L, redis.opsForStream().pending(stream, "quota-dispatch").getTotalPendingMessages());
                    admin.declareQueue(queue);
                    Thread.sleep(30_100);
                    var restarted = new QuotaStreamDispatcher(context.getBean(QuotaActivityMapper.class), redis, rabbit, queue, json);
                    restarted.dispatchActivity(id);
                    var delivered = rabbit.receive(tag, 5000);
                    assertNotNull(delivered);
                    assertEquals(number, delivered.getMessageProperties().getMessageId());
                    assertEquals(MessageDeliveryMode.PERSISTENT, delivered.getMessageProperties().getReceivedDeliveryMode());
                    var payload = json.readTree(delivered.getBody());
                    assertEquals(number, payload.get("claimNo").asText());
                    assertEquals(id, payload.get("activityId").asLong());
                    assertEquals(owner, payload.get("userId").asLong());
                    assertEquals(5, payload.get("grantAmount").asInt());
                    assertEquals(0L, redis.opsForStream().size(stream));
                    assertEquals(1L, redis.opsForHash().size(ActivityRedis.prefix(id) + "pending"));
                    assertEquals("PROCESSING", claims.find(id, owner).getStatus());
                    // 仅丢失库存可按预扣记录恢复，规则和 Stream 也丢失时必须暂停恢复。
                    String stock = ActivityRedis.prefix(id) + "stock";
                    String rules = ActivityRedis.prefix(id) + "rules";
                    redis.delete(stock);
                    assertEquals(0L, service.recoverStock(id));
                    redis.delete(List.of(stock, rules, stream));
                    assertThrows(BusinessException.class, () -> service.recoverStock(id));
                    assertThrows(BusinessException.class, () -> claims.claim(id, other));
                    assertFalse(redis.hasKey(stock));
                    assertFalse(redis.hasKey(rules));
                    assertFalse(redis.hasKey(stream));
                }
            } finally {
                try {
                    admin.deleteQueue(tag);
                } finally {
                    redis.delete(List.of("outbox","pending","rules","reservations","stock","config","config-version").stream()
                            .map(suffix -> ActivityRedis.prefix(id) + suffix).toList());
                    jdbc.update("DELETE FROM quota_activity WHERE id=? AND name=?", id, tag);
                    jdbc.update("DELETE FROM `user` WHERE userAccount IN (?,?) AND nickname=?", tag, otherAccount, tag);
                }
            }
        }
    }
}
