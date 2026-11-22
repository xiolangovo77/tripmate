package com.ecnu.tripmate.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quota.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class QuotaGrantConsumerConfig {
    @Bean
    public Declarables quotaDeadLetters(@Value("${quota.dispatch.queue}") String queueName) {
        return new Declarables(new Queue(queueName + ".dead", true));
    }

    @Bean
    public QuotaGrantConsumer quotaGrantConsumer(QuotaGrantService quotaGrantService, ObjectMapper objectMapper,
            RabbitTemplate rabbitTemplate, @Value("${quota.dispatch.queue}") String queueName) {
        return new QuotaGrantConsumer(quotaGrantService, objectMapper, rabbitTemplate, queueName + ".dead");
    }

    @Bean
    public SimpleMessageListenerContainer quotaGrantContainer(ConnectionFactory connectionFactory,
            QuotaGrantConsumer quotaGrantConsumer, @Value("${quota.dispatch.queue}") String queueName,
            @Value("${quota.consumer.prefetch:16}") int prefetch) {
        //1、使用发放队列专属容器，逐条手动确认。
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        //2、限制单个消费者的未确认投递数，并注册入账监听器。
        container.setPrefetchCount(prefetch);
        container.setMessageListener(quotaGrantConsumer);
        return container;
    }
}
