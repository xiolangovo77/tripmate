package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.mapper.QuotaGrantMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.spring.boot.FlexTransactionAutoConfiguration;
import com.mybatisflex.spring.boot.MybatisFlexAutoConfiguration;
import com.rabbitmq.client.Channel;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ConsumeOkEvent;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 独立验收进程，仅在测试类路径提供提交后拦截点。 */
public class QuotaGrantRecoveryNode {
    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = QuotaGrantMapper.class)
    @EnableTransactionManagement
    @Import({QuotaGrantService.class, QuotaDispatchConfig.class})
    @ImportAutoConfiguration({RabbitAutoConfiguration.class, JacksonAutoConfiguration.class,
            DataSourceAutoConfiguration.class, MybatisFlexAutoConfiguration.class,
            FlexTransactionAutoConfiguration.class, TransactionAutoConfiguration.class})
    static class DatabaseConfig {}

    @TestConfiguration(proxyBeanMethods = false)
    @Import({DatabaseConfig.class, QuotaGrantConsumerConfig.class})
    static class WorkerConfig {
        /** 本次 RabbitMQ 确认订阅的单调时钟起点，单位为纳秒。 */
        private final AtomicLong subscribed = new AtomicLong();

        @Bean
        ApplicationListener<ConsumeOkEvent> subscribedListener(org.springframework.core.env.Environment env) {
            return event -> {
                if (subscribed.compareAndSet(0, System.nanoTime())) {
                    long start = env.getRequiredProperty("probe.started-ms", Long.class);
                    System.out.println("PROBE_STARTUP_MS " + (System.currentTimeMillis() - start));
                }
            };
        }

        @Bean
        @Primary
        QuotaGrantConsumer probe(QuotaGrantService service, ObjectMapper mapper, RabbitTemplate template,
                org.springframework.core.env.Environment env) {
            String queue = env.getRequiredProperty("quota.dispatch.queue");
            int expected = env.getRequiredProperty("probe.count", Integer.class);
            boolean pause = env.getRequiredProperty("probe.pause", Boolean.class);
            return new QuotaGrantConsumer(service, mapper, template, queue + ".dead") {
                /** 本进程已经完成入账事务的消息数。 */
                private int processed;

                @Override
                void acknowledgeCommitted(Message message, Channel channel) throws IOException {
                    System.out.println("PROBE_DELIVERY " + message.getMessageProperties().getMessageId()
                            + " " + message.getMessageProperties().isRedelivered());
                    processed++;
                    if (!pause) super.acknowledgeCommitted(message, channel);
                    if (processed != expected) return;
                    if (pause) {
                        System.out.println("PROBE_PAUSED");
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    } else {
                        // 同一信道的同步命令在前面的 ACK 之后完成。
                        channel.queueDeclarePassive(queue);
                        if (subscribed.get() == 0) throw new IOException("缺少订阅确认时间");
                        System.out.println("PROBE_DRAIN_MS " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - subscribed.get()));
                    }
                }
            };
        }
    }

    public static void main(String[] args) throws InterruptedException {
        new SpringApplicationBuilder(WorkerConfig.class).web(WebApplicationType.NONE).run(args);
        new CountDownLatch(1).await();
    }
}
