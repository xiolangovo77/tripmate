package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.quota.model.ActivityConfig;
import com.ecnu.tripmate.quota.model.ActivityConfigChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ActivityCacheEvents {
    /**
     * 清理共享配置缓存并广播活动版本变更。
     */
    private final ActivityRedis activityRedis;

    /**
     * 使本机旧配置失效，并在活动发布后预热配置。
     */
    private final ActivityCache activityCache;

    /**
     * 按活动 ID 保留同步失败后待重试的最新配置变更事件。
     */
    private final ConcurrentMap<Long, ActivityConfigChangedEvent> pending = new ConcurrentHashMap<>();

    @Bean
    public RedisMessageListenerContainer quotaActivityListener(RedisConnectionFactory redisConnectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener((message, pattern) -> {
            String[] fields = new String(message.getBody(), StandardCharsets.UTF_8).split(":", 2);
            if (fields.length == 2) {
                activityCache.invalidate(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
            }
        }, new ChannelTopic(ActivityRedis.CHANNEL));
        return container;
    }

    @TransactionalEventListener
    public void afterCommit(ActivityConfigChangedEvent event) {
        deliver(event);
    }

    @Scheduled(fixedDelay = 5000)
    public void retry() {
        pending.forEach((id, event) -> deliver(event));
    }

    private void deliver(ActivityConfigChangedEvent event) {
        ActivityConfig config = event.getConfig();
        try {
            //1、先使共享缓存和本机旧版本失效，再通知其他实例。
            activityRedis.invalidate(config.getId(), config.getConfigVersion());
            activityCache.invalidate(config.getId(), config.getConfigVersion());
            activityRedis.broadcast(config.getId(), config.getConfigVersion());
            //2、发布活动时预热配置，成功后移除对应的待重试事件。
            if (event.isPrewarm()) {
                activityCache.prewarm(config);
            }
            pending.remove(config.getId(), event);
        } catch (RuntimeException e) {
            activityCache.invalidate(config.getId(), config.getConfigVersion());
            //3、同步失败时保留该活动的最新事件，供定时任务重试。
            pending.merge(config.getId(), event, (a, b) ->
                    a.getConfig().getConfigVersion() >= b.getConfig().getConfigVersion() ? a : b);
            log.warn("活动缓存同步待重试, activityId={}, version={}", config.getId(), config.getConfigVersion(), e);
        }
    }
}
