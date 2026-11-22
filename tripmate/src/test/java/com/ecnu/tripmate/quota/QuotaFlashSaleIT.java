package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.cache.quota.ActivityRedis;
import com.ecnu.tripmate.controller.QuotaClaimController;
import com.ecnu.tripmate.controller.UserController;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.quota.config.QuotaDispatchConfig;
import com.ecnu.tripmate.quota.config.QuotaGrantConsumerConfig;
import com.ecnu.tripmate.quota.model.CreateActivityRequest;
import com.ecnu.tripmate.service.QuotaActivityService;
import com.ecnu.tripmate.service.QuotaClaimService;
import com.ecnu.tripmate.service.QuotaGrantService;
import com.ecnu.tripmate.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "quota.dev.it", matches = "true")
class QuotaFlashSaleIT {
    @TestConfiguration(proxyBeanMethods = false)
    @Import({QuotaBenchmarkNode.NodeConfig.class, UserController.class, QuotaClaimController.class,
            QuotaClaimService.class, QuotaDispatchConfig.class, QuotaGrantConsumerConfig.class, QuotaGrantService.class})
    @ImportAutoConfiguration({RabbitAutoConfiguration.class, EmbeddedWebServerFactoryCustomizerAutoConfiguration.class})
    static class Config {}

    @Test
    void thousandMembersCompeteForHundredGrants() throws Exception {
        String tag = "flash-" + UUID.randomUUID();
        try (var ctx = (ServletWebServerApplicationContext) SpringApplication.run(Config.class,
                "--spring.profiles.active=dev", "--server.address=127.0.0.1", "--server.port=0",
                "--server.tomcat.accept-count=1024", "--quota.dispatch.enabled=false", "--quota.dispatch.queue=" + tag,
                "--logging.level.root=WARN", "--logging.level.com.ecnu.tripmate.exception.GlobalExceptionHandler=OFF");
             var httpPool = Executors.newFixedThreadPool(24);
             var http = HttpClient.newBuilder().executor(httpPool).version(HttpClient.Version.HTTP_1_1).build()) {
            URI base = URI.create("http://127.0.0.1:" + ctx.getWebServer().getPort() + ctx.getServletContext().getContextPath() + "/");
            var jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
            var redis = ctx.getBean(StringRedisTemplate.class);
            var json = ctx.getBean(ObjectMapper.class);
            var service = ctx.getBean(QuotaActivityService.class);
            var admin = ctx.getBean(AmqpAdmin.class);
            var draft = new CreateActivityRequest();
            draft.setName(tag);
            draft.setRuleText("并发领取验收");
            draft.setStartTime(LocalDateTime.now().minusHours(1));
            draft.setEndTime(LocalDateTime.now().plusHours(1));
            draft.setTotalStock(100L);
            draft.setGrantAmount(5L);
            long id = service.create(draft);
            String prefix = ActivityRedis.prefix(id);
            try {
                service.publish(id);
                //1、创建独立会员，通过真实 HTTP 登录，每人持有自己的会话。
                String password = UUID.randomUUID().toString();
                String encrypted = ctx.getBean(UserService.class).getEncryptPassword(password);
                var accounts = IntStream.range(0, 1000).mapToObj(i -> tag + "-" + i).toList();
                jdbc.batchUpdate("INSERT INTO `user`(userAccount,password,nickname,memberExpireAt) VALUES(?,?,?,DATE_ADD(NOW(), INTERVAL 1 DAY))",
                        accounts, 200, (ps, account) -> {
                            ps.setString(1, account);
                            ps.setString(2, encrypted);
                            ps.setString(3, tag);
                        });
                Map<Long, String> cookies = new HashMap<>();
                try (var logins = Executors.newFixedThreadPool(16)) {
                    List<Callable<Map.Entry<Long, String>>> tasks = new ArrayList<>();
                    for (String account : accounts) tasks.add(() -> {
                        String payload = json.writeValueAsString(Map.of("userAccount", account, "userPassword", password));
                        var request = HttpRequest.newBuilder(base.resolve("user/login")).timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        JsonNode result = json.readTree(response.body());
                        assertEquals(0, result.path("code").intValue(), result.toString());
                        long user = result.at("/data/id").asLong();
                        assertTrue(user > 0);
                        return Map.entry(user, response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0]);
                    });
                    for (var login : logins.invokeAll(tasks)) {
                        var entry = login.get();
                        cookies.put(entry.getKey(), entry.getValue());
                    }
                }
                assertEquals(1000, cookies.size());
                assertEquals(1000, new HashSet<>(cookies.values()).size());

                //2、全部虚拟用户就绪后一起放行，每个会员仅发送一次领取请求。
                var ready = new CountDownLatch(1000);
                var start = new CountDownLatch(1);
                Map<Long, String> winners = new HashMap<>();
                int soldOut = 0;
                long elapsed;
                try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
                    Map<Long, Future<JsonNode>> responses = new HashMap<>();
                    for (var entry : cookies.entrySet()) {
                        var request = HttpRequest.newBuilder(base.resolve("quota/activities/" + id + "/claims"))
                                .timeout(Duration.ofSeconds(90)).header("Cookie", entry.getValue())
                                .POST(HttpRequest.BodyPublishers.noBody()).build();
                        responses.put(entry.getKey(), clients.submit(() -> {
                            ready.countDown();
                            if (!start.await(30, TimeUnit.SECONDS)) throw new TimeoutException("并发起跑超时");
                            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                            assertEquals(200, response.statusCode());
                            return json.readTree(response.body());
                        }));
                    }
                    assertTrue(ready.await(30, TimeUnit.SECONDS));
                    long begin = System.nanoTime();
                    start.countDown();
                    for (var entry : responses.entrySet()) {
                        JsonNode result = entry.getValue().get();
                        if (result.path("code").asInt(-1) == 0) {
                            assertEquals("PROCESSING", result.at("/data/status").asText());
                            winners.put(entry.getKey(), result.at("/data/claimNo").asText());
                        } else {
                            assertEquals(ErrorCode.OPERATION_ERROR.getCode(), result.path("code").asInt());
                            assertEquals("活动已售罄", result.path("message").asText());
                            soldOut++;
                        }
                    }
                    elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                }
                assertEquals(100, winners.size());
                assertEquals(100, new HashSet<>(winners.values()).size());
                assertEquals(900, soldOut);
                assertEquals(0L, service.detail(id).getRemainingStock());
                var stream = prefix + "outbox";
                assertEquals(100L, redis.opsForStream().size(stream));
                var reserved = redis.<String,String>opsForHash().entries(prefix + "reservations");
                assertEquals("100", reserved.remove("_total"));
                assertEquals(100, reserved.size());
                winners.forEach((user, number) -> assertEquals(number, reserved.get(user.toString())));

                //3、只投递测试活动的 Stream，由生产消费者完成真实事务入账。
                var dispatcher = new QuotaStreamDispatcher(ctx.getBean(QuotaActivityMapper.class), redis,
                        ctx.getBean(RabbitTemplate.class), ctx.getBean(Queue.class), json);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (redis.opsForStream().size(stream) > 0) {
                    assertTrue(System.nanoTime() < deadline);
                    dispatcher.dispatchActivity(id);
                }
                while (jdbc.queryForObject("SELECT COUNT(*) FROM quota_claim WHERE activityId=? AND status='granted'",
                        Integer.class, id) < 100) {
                    assertTrue(System.nanoTime() < deadline, "等待入账超时");
                    Thread.sleep(50);
                }
                //4、按请求返回的领取人和单号逐笔对账，同时检查测试人群的全部余额与流水。
                var rows = jdbc.queryForList("SELECT c.claimNo,c.userId,c.status,c.grantAmount,a.balance,f.amount,f.flowType "
                        + "FROM quota_claim c JOIN quota_account a ON a.userId=c.userId "
                        + "JOIN quota_flow f ON f.claimNo=c.claimNo AND f.userId=c.userId WHERE c.activityId=?", id);
                assertEquals(100, rows.size());
                Map<Long, String> actual = new HashMap<>();
                for (var row : rows) {
                    long user = ((Number) row.get("userId")).longValue();
                    assertNull(actual.put(user, (String) row.get("claimNo")));
                    assertEquals("granted", row.get("status"));
                    assertEquals("grant", row.get("flowType"));
                    for (String field : List.of("grantAmount", "balance", "amount")) {
                        assertEquals(5L, ((Number) row.get(field)).longValue());
                    }
                }
                assertEquals(winners, actual);
                assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM quota_claim WHERE activityId=?", Integer.class, id));
                assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM quota_flow f JOIN `user` u ON u.id=f.userId WHERE u.nickname=?", Integer.class, tag));
                assertEquals(500L, jdbc.queryForObject("SELECT SUM(a.balance) FROM quota_account a JOIN `user` u ON u.id=a.userId WHERE u.nickname=?", Long.class, tag));
                assertEquals(1L, redis.opsForHash().size(prefix + "pending"));
                assertEquals(0, admin.getQueueInfo(tag + ".dead").getMessageCount());
                System.out.printf("FLASH_VERIFIED activity=%d members=%d accepted=%d soldOut=%d stock=0 granted=%d flows=100 balance=500 oversold=0 elapsedHttpMs=%d%n",
                        id, cookies.size(), winners.size(), soldOut, actual.size(), elapsed);
            } finally {
                ctx.getBean("quotaGrantContainer", org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class).stop();
                admin.deleteQueue(tag);
                admin.deleteQueue(tag + ".dead");
                redis.delete(List.of("stock","rules","reservations","pending","outbox","config","config-version").stream().map(s -> prefix + s).toList());
                jdbc.update("DELETE f FROM quota_flow f JOIN `user` u ON f.userId=u.id WHERE u.nickname=?", tag);
                jdbc.update("DELETE a FROM quota_account a JOIN `user` u ON a.userId=u.id WHERE u.nickname=?", tag);
                jdbc.update("DELETE FROM quota_claim WHERE activityId=?", id);
                jdbc.update("DELETE FROM quota_activity WHERE id=? AND name=?", id, tag);
                jdbc.update("DELETE FROM `user` WHERE nickname=?", tag);
            }
        }
    }
}
