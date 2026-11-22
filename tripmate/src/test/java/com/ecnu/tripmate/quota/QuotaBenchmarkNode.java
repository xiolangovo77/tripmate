package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.cache.quota.ActivityCache;
import com.ecnu.tripmate.cache.quota.ActivityCacheEvents;
import com.ecnu.tripmate.cache.quota.ActivityRedis;
import com.ecnu.tripmate.controller.QuotaActivityController;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.GlobalExceptionHandler;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.quota.model.CreateActivityRequest;
import com.ecnu.tripmate.quota.model.UpdateActivityDisplayRequest;
import com.ecnu.tripmate.service.QuotaActivityService;
import com.ecnu.tripmate.service.impl.UserServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.protocol.CommandType;
import com.mybatisflex.spring.boot.FlexTransactionAutoConfiguration;
import com.mybatisflex.spring.boot.MybatisFlexAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 使用实际活动模块和 dev 数据源的本地 HTTP 节点，由压测脚本通过标准输入管理测试数据。 */
public class QuotaBenchmarkNode {
    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = QuotaActivityMapper.class)
    @Import({QuotaActivityController.class, UserServiceImpl.class, GlobalExceptionHandler.class,
            QuotaActivityService.class, ActivityCache.class, ActivityRedis.class, ActivityCacheEvents.class})
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            DataSourceAutoConfiguration.class, RedisAutoConfiguration.class, MybatisFlexAutoConfiguration.class,
            FlexTransactionAutoConfiguration.class, TransactionAutoConfiguration.class})
    static class NodeConfig {
        @Bean
        @ConditionalOnProperty(name = "quota.benchmark.count-gets", havingValue = "true")
        static QuotaReadCounters quotaReadCounters() {
            return new QuotaReadCounters();
        }
    }

    private final QuotaActivityService service;
    private final QuotaActivityMapper mapper;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final String name = "quota-bench-" + UUID.randomUUID();
    private long id;

    private QuotaBenchmarkNode(ServletWebServerApplicationContext context) {
        service = context.getBean(QuotaActivityService.class);
        mapper = context.getBean(QuotaActivityMapper.class);
        redis = context.getBean(StringRedisTemplate.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        objectMapper = context.getBean(ObjectMapper.class);
    }

    private void setup() throws Exception {
        assertEquals(0, id);
        var draft = new CreateActivityRequest();
        draft.setName(name);
        draft.setRuleText("每人一份，每份五次");
        draft.setStartTime(LocalDateTime.now().minusHours(1));
        draft.setEndTime(LocalDateTime.now().plusHours(1));
        draft.setGrantAmount(5L);
        draft.setTotalStock(100L);
        id = service.create(draft);
        assertEquals(name, mapper.select(id).getName());
        assertThrows(BusinessException.class, () -> service.detail(id));
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> publish = () -> {
                start.await(10, TimeUnit.SECONDS);
                service.publish(id);
                return null;
            };
            var both = pool.invokeAll(List.of(publish, publish));
            for (var task : both) task.get();
        }
        assertEquals(2, mapper.select(id).getConfigVersion());
        assertEquals(100L, service.detail(id).getRemainingStock());
        redis.opsForValue().set(prefix() + "stock", "98");
        service.publish(id);
        assertEquals(98L, service.detail(id).getRemainingStock());
    }

    private void update(boolean rollback) {
        assertTrue(id > 0);
        var display = new UpdateActivityDisplayRequest();
        display.setName(name);
        display.setRuleText(rollback ? "回滚测试" : "每位会员限领一份，每份五次");
        transaction.executeWithoutResult(status -> {
            service.updateDisplay(id, display);
            if (rollback) status.setRollbackOnly();
        });
        assertEquals(3, mapper.select(id).getConfigVersion());
        assertEquals("每位会员限领一份，每份五次", mapper.select(id).getRuleText());
    }

    private String prefix() {
        return "tripmate:quota:activity:{" + id + "}:";
    }

    /** 独立客户端只统计本次活动的 GET；采样在计时压测之外进行。 */
    private void probeReads() throws Exception {
        var original = (LettuceConnectionFactory) redis.getConnectionFactory();
        var factory = new LettuceConnectionFactory(original.getStandaloneConfiguration(), original.getClientConfiguration());
        factory.afterPropertiesSet();
        try {
            var configReads = new AtomicInteger();
            var stockReads = new AtomicInteger();
            String configKey = prefix() + "config";
            String stockKey = prefix() + "stock";
            factory.getRequiredNativeClient().addListener(new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    var command = event.getCommand();
                    if (command.getType() != CommandType.GET) return;
                    String key = StandardCharsets.UTF_8.decode(command.getArgs().getFirstEncodedKey().duplicate()).toString();
                    if (configKey.equals(key)) configReads.incrementAndGet();
                    if (stockKey.equals(key)) stockReads.incrementAndGet();
                }
            });
            var storage = new ActivityRedis(new StringRedisTemplate(factory), objectMapper);
            for (boolean local : new boolean[]{false, true}) {
                var cache = new ActivityCache(mapper, storage, local, 1024);
                var reader = new QuotaActivityService(mapper, cache, storage, event -> {});
                try (var pool = Executors.newFixedThreadPool(10)) {
                    for (int batch = 0; batch < 2; batch++) {
                        configReads.set(0);
                        stockReads.set(0);
                        var start = new CyclicBarrier(10);
                        var calls = IntStream.range(0, 10).<Callable<Long>>mapToObj(i -> () -> {
                            start.await(5, TimeUnit.SECONDS);
                            return reader.detail(id).getRemainingStock();
                        }).toList();
                        for (var response : pool.invokeAll(calls)) assertEquals(98L, response.get());
                        int expected = local ? (batch == 0 ? 1 : 0) : 10;
                        assertEquals(expected, configReads.get(), "configuration GET count");
                        assertEquals(10, stockReads.get(), "stock GET count");
                        System.out.printf("GETCOUNTS %b %d %d %d%n", local, batch, configReads.get(), stockReads.get());
                    }
                }
            }
        } finally {
            factory.destroy();
        }
    }

    private void recovery() {
        assertTrue(id > 0);
        String stock = prefix() + "stock";
        String reserved = prefix() + "reservations";
        String pending = prefix() + "pending";
        redis.opsForValue().set(stock, "0");
        assertEquals(0L, service.recoverStock(id));
        redis.opsForHash().put(reserved, "1", name + "-1");
        redis.opsForHash().put(reserved, "2", name + "-2");
        redis.opsForHash().put(reserved, "3", name + "-3");
        redis.opsForHash().put(pending, "2", name + "-2");
        redis.opsForStream().add(prefix() + "outbox", java.util.Map.of("claimNo", name + "-2",
                "activityId", Long.toString(id), "userId", "2", "grantAmount", "5"));
        jdbc.update("INSERT INTO quota_claim(activityId,userId,claimNo,grantAmount) VALUES(?,?,?,?)", id, 1, name+"-1", 5);
        redis.delete(stock);
        assertFalse(service.detail(id).isStockAvailable());
        assertNull(service.detail(id).getRemainingStock());
        assertEquals(97L, service.recoverStock(id));
        redis.delete(stock);
        redis.opsForHash().put(reserved, "1", "mismatch");
        assertThrows(BusinessException.class, () -> service.recoverStock(id));
        assertFalse(redis.hasKey(stock));
        redis.opsForHash().put(reserved, "1", name + "-1");
        assertEquals(97L, service.recoverStock(id));
    }

    private void clean() {
        if (id == 0) return;
        assertEquals(name, mapper.select(id).getName());
        jdbc.update("DELETE FROM quota_claim WHERE activityId=? AND claimNo=?", id, name+"-1");
        var keys = List.of("stock", "reservations", "pending", "rules", "outbox", "config", "config-version").stream()
                .map(k -> prefix()+k).toList();
        redis.delete(keys);
        assertEquals(0L, redis.countExistingKeys(keys));
        assertEquals(1, jdbc.update("DELETE FROM quota_activity WHERE id=? AND name=?", id, name));
        assertNull(mapper.select(id));
        id = 0;
    }

    public static void main(String[] args) throws Exception {
        try (var context = (ServletWebServerApplicationContext) SpringApplication.run(NodeConfig.class,
                "--spring.profiles.active=dev", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                "--quota.cache.local-enabled=" + args[0],
                "--quota.benchmark.count-gets=" + (args.length > 1 && args[1].equals("metrics")))) {
            var node = new QuotaBenchmarkNode(context);
            var input = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("BENCH READY " + context.getWebServer().getPort());
            try {
                for (String command; (command = input.readLine()) != null;) {
                    if (command.startsWith("measure ")) {
                        long activityId = Long.parseLong(command.substring("measure ".length()));
                        context.getBean(QuotaReadCounters.class).begin(activityId, context.getBean(ActivityCache.class));
                        System.out.println("BENCH OK " + activityId);
                        continue;
                    }
                    switch (command) {
                        case "counts" -> context.getBean(QuotaReadCounters.class).finish(context.getBean(ActivityCache.class));
                        case "setup" -> node.setup();
                        case "update" -> node.update(false);
                        case "rollback" -> node.update(true);
                        case "recover" -> node.recovery();
                        case "reads" -> node.probeReads();
                        case "clean" -> node.clean();
                        case "exit" -> { return; }
                        default -> throw new IllegalArgumentException("unknown command");
                    }
                    System.out.println("BENCH OK " + node.id);
                }
            } finally {
                node.clean();
            }
        }
    }
}
