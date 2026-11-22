package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.quota.model.QuotaGrantMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "quota.dev.it", matches = "true")
class QuotaGrantRecoveryIT {
    /** 子进程启动参数和故障验收日志的临时目录。 */
    @TempDir Path directory;
    /** 仅用于本次测试的队列及数据标记。 */
    private final String tag = "grant-it-" + UUID.randomUUID();
    /** 本用例创建的独立消费者进程。 */
    private Process worker;
    /** 当前子进程的日志。 */
    private Path output;
    /** 临时活动和数据库断言。 */
    private JdbcTemplate jdbcTemplate;
    /** 发布与读取测试消息。 */
    private RabbitTemplate rabbitTemplate;
    /** 编解码消息及管理接口响应。 */
    private ObjectMapper objectMapper;
    /** 用于核对 broker 端未确认计数的开发环境连接设置。 */
    private RabbitProperties rabbitProperties;
    /** 本次验收活动的 ID。 */
    private long activityId;

    @Test
    void verifyRecoveryAndFailures() throws Exception {
        try (var context = new SpringApplicationBuilder(QuotaGrantRecoveryNode.DatabaseConfig.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=dev", "--quota.dispatch.queue=" + tag)) {
            jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            rabbitTemplate = context.getBean(RabbitTemplate.class);
            rabbitProperties = context.getBean(RabbitProperties.class);
            objectMapper = context.getBean(ObjectMapper.class);
            var admin = context.getBean(AmqpAdmin.class);
            try {
                admin.declareQueue(new org.springframework.amqp.core.Queue(tag, true));
                admin.declareQueue(new org.springframework.amqp.core.Queue(tag + ".dead", true));
                jdbcTemplate.update("INSERT INTO quota_activity(name,ruleText,startTime,endTime,totalStock,grantAmount,status) "
                        + "VALUES(?,?,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),200,5,'published')", tag, tag);
                activityId = jdbcTemplate.queryForObject("SELECT id FROM quota_activity WHERE name=?", Long.class, tag);
                //1、先验收单条提交后宕机，再让 100 条分别提交并保持未确认。
                verifyCrash(1);
                verifyCrash(100);
                //2、复用重启后的消费进程检查冲突、事务回滚、自动重试和死信。
                verifyFailures();
            } finally {
                stopWorker();
                try {
                    admin.deleteQueue(tag);
                    admin.deleteQueue(tag + ".dead");
                } finally {
                    jdbcTemplate.update("DELETE f FROM quota_flow f JOIN `user` u ON f.userId=u.id WHERE u.nickname=?", tag);
                    jdbcTemplate.update("DELETE a FROM quota_account a JOIN `user` u ON a.userId=u.id WHERE u.nickname=?", tag);
                    jdbcTemplate.update("DELETE FROM quota_claim WHERE activityId=?", activityId);
                    jdbcTemplate.update("DELETE FROM quota_activity WHERE id=? AND name=?", activityId, tag);
                    jdbcTemplate.update("DELETE FROM `user` WHERE nickname=?", tag);
                }
            }
        }
    }

    private void verifyCrash(int count) throws Exception {
        stopWorker();
        // 等待 broker 解除旧订阅，避免新消息先投给已强停的连接。
        var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbitTemplate.getConnectionFactory());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (admin.getQueueInfo(tag).getConsumerCount() != 0) {
            assertTrue(System.nanoTime() < deadline, "旧订阅未释放");
            Thread.sleep(100);
        }
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < count; i++) {
            QuotaGrantMessage message = newMessage();
            numbers.add(message.getClaimNo());
            send(message);
        }
        startWorker(count, true);
        waitLog("PROBE_PAUSED");
        await(() -> brokerCount("messages_unacknowledged") == count, "broker unacked=" + count);
        assertAccounts(numbers);
        assertReceipts(numbers, false);
        stopWorker();
        startWorker(count, false);
        waitLog("PROBE_DRAIN_MS");
        assertReceipts(numbers, true);
        assertAccounts(numbers);
        await(() -> brokerCount("messages") == 0, "ready 和 unacked 清空");
        assertNull(rabbitTemplate.receive(tag + ".dead"));
        System.out.println("RECOVERY_VERIFIED count=" + count + " " + log().lines()
                .filter(line -> line.startsWith("PROBE_DRAIN_MS") || line.startsWith("PROBE_STARTUP_MS"))
                .collect(java.util.stream.Collectors.joining(" ")));
    }

    private void assertAccounts(Set<String> numbers) {
        var rows = jdbcTemplate.queryForList("SELECT c.claimNo,c.status,c.grantAmount,a.balance,f.flowType,f.amount "
                + "FROM quota_claim c JOIN quota_account a ON c.userId=a.userId "
                + "JOIN quota_flow f ON c.claimNo=f.claimNo AND c.userId=f.userId WHERE c.activityId=?", activityId);
        Set<String> verified = new HashSet<>();
        for (var row : rows) {
            String number = (String) row.get("claimNo");
            if (!numbers.contains(number)) continue;
            assertTrue(verified.add(number));
            assertEquals("granted", row.get("status"));
            assertEquals("grant", row.get("flowType"));
            for (String field : List.of("grantAmount", "balance", "amount")) {
                assertEquals(5L, ((Number) row.get(field)).longValue());
            }
        }
        assertEquals(numbers, verified);
    }

    private void assertReceipts(Set<String> numbers, boolean redelivered) {
        var receipts = log().lines().filter(line -> line.startsWith("PROBE_DELIVERY ")).toList();
        assertEquals(numbers.size(), receipts.size());
        for (String number : numbers) {
            assertTrue(receipts.contains("PROBE_DELIVERY " + number + " " + redelivered),
                    () -> "投递记录不匹配：" + number + " " + receipts);
        }
    }

    private void verifyFailures() throws Exception {
        var temporary = newMessage();
        overflowAccount(temporary);
        send(temporary);
        waitLog("次失败，1 秒后重试，claimNo=" + temporary.getClaimNo());
        assertRolledBack(temporary);
        jdbcTemplate.update("UPDATE quota_account SET balance=0 WHERE userId=?", temporary.getUserId());
        await(() -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quota_claim WHERE claimNo=? AND status='granted'",
                Integer.class, temporary.getClaimNo()) == 1, "短暂故障后到账");
        assertAccounts(Set.of(temporary.getClaimNo()));

        var conflicting = new QuotaGrantMessage(number(), activityId, temporary.getUserId(), 5);
        send(conflicting);
        assertDead(conflicting, "冲突");
        send(new QuotaGrantMessage(temporary.getClaimNo(), activityId, temporary.getUserId(), 4));
        assertDead(temporary, "冲突");
        assertAccounts(Set.of(temporary.getClaimNo()));

        var exhausted = newMessage();
        overflowAccount(exhausted);
        send(exhausted);
        assertDead(exhausted, "Exception");
        assertEquals(3, log().lines().filter(line -> line.contains("秒后重试，claimNo=" + exhausted.getClaimNo())).count());
        assertRolledBack(exhausted);
    }

    private void assertDead(QuotaGrantMessage expected, String reason) throws Exception {
        Message dead = rabbitTemplate.receive(tag + ".dead", 20000);
        assertNotNull(dead);
        assertEquals(expected.getClaimNo(), dead.getMessageProperties().getMessageId());
        assertEquals(expected.getClaimNo(), objectMapper.readTree(dead.getBody()).path("claimNo").textValue());
        assertTrue(dead.getMessageProperties().getHeaders().get("quota-failure").toString().contains(reason));
    }

    private void overflowAccount(QuotaGrantMessage message) {
        jdbcTemplate.update("INSERT INTO quota_account(userId,balance) VALUES(?,18446744073709551615)", message.getUserId());
    }

    private void assertRolledBack(QuotaGrantMessage message) {
        for (String table : List.of("quota_flow", "quota_claim")) {
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE claimNo=?",
                    Integer.class, message.getClaimNo()));
        }
        assertEquals("18446744073709551615", jdbcTemplate.queryForObject(
                "SELECT CAST(balance AS CHAR) FROM quota_account WHERE userId=?", String.class, message.getUserId()));
    }

    private String number() {
        return activityId + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private QuotaGrantMessage newMessage() {
        String account = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO `user`(userAccount,password,nickname) VALUES(?,?,?)", account, tag, tag);
        long userId = jdbcTemplate.queryForObject("SELECT id FROM `user` WHERE userAccount=?", Long.class, account);
        return new QuotaGrantMessage(number(), activityId, userId, 5);
    }

    private void send(QuotaGrantMessage grant) throws Exception {
        var message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(grant))
                .setMessageId(grant.getClaimNo()).setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
        rabbitTemplate.send("", tag, message);
    }

    private void startWorker(int count, boolean pause) throws Exception {
        Path args = directory.resolve("classpath.args");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(args, "-classpath\n\"" + cp.replace("\\", "/") + "\"\n");
        output = Files.createTempFile(directory, "consumer-", ".log");
        worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + args, "-Dfile.encoding=UTF-8", QuotaGrantRecoveryNode.class.getName(),
                "--spring.profiles.active=dev", "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                "--quota.dispatch.queue=" + tag, "--quota.consumer.prefetch=" + count,
                "--probe.count=" + count, "--probe.pause=" + pause, "--probe.started-ms=" + System.currentTimeMillis())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private int brokerCount(String field) {
        try {
            String vhost = rabbitProperties.getVirtualHost();
            if (vhost == null) vhost = "/";
            URI uri = new URI("http://" + rabbitProperties.getHost() + ":15672/api/queues/"
                    + URLEncoder.encode(vhost, StandardCharsets.UTF_8) + "/" + tag);
            String auth = rabbitProperties.getUsername() + ":" + rabbitProperties.getPassword();
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8))).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            return objectMapper.readTree(response.body()).path(field).asInt(-1);
        } catch (Exception e) {
            throw new IllegalStateException("读取测试队列计数失败", e);
        }
    }

    private String log() {
        try {
            return Files.readString(output);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void waitLog(String marker) throws Exception {
        await(() -> log().contains(marker), marker);
    }

    private void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            assertTrue(worker.isAlive(), "子进程退出：" + log());
            assertTrue(System.nanoTime() < deadline, "等待超时：" + description + "\n" + log());
            Thread.sleep(100);
        }
    }

    private void stopWorker() throws Exception {
        if (worker != null && worker.isAlive()) {
            worker.destroyForcibly();
            assertTrue(worker.waitFor(15, TimeUnit.SECONDS));
        }
    }
}
