package com.ecnu.tripmate.quota.config;

import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QuotaDispatchConfig {
    @Bean
    public Queue quotaGrantQueue(@Value("${quota.dispatch.queue}") String queueName) {
        return new Queue(queueName, true);
    }
}
