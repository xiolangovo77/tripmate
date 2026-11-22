package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.cache.quota.ActivityCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.protocol.CommandType;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

/** 压测专用：只统计当前测试活动的配置和库存 GET。 */
class QuotaReadCounters implements BeanPostProcessor, CommandListener {
    private final LongAdder configGets = new LongAdder();
    private final LongAdder stockGets = new LongAdder();
    private ByteBuffer configKey;
    private ByteBuffer stockKey;
    private CacheStats before;
    private volatile boolean active;

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof LettuceConnectionFactory factory) {
            factory.getRequiredNativeClient().addListener(this);
        }
        return bean;
    }

    void begin(long activityId, ActivityCache cache) {
        String prefix = "tripmate:quota:activity:{" + activityId + "}:";
        configKey = StandardCharsets.UTF_8.encode(prefix + "config");
        stockKey = StandardCharsets.UTF_8.encode(prefix + "stock");
        configGets.reset();
        stockGets.reset();
        before = cache.snapshotStats();
        active = true;
    }

    @Override
    public void commandStarted(CommandStartedEvent event) {
        if (!active || event.getCommand().getType() != CommandType.GET) return;
        ByteBuffer key = event.getCommand().getArgs().getFirstEncodedKey();
        if (configKey.equals(key)) configGets.increment();
        if (stockKey.equals(key)) stockGets.increment();
    }

    void finish(ActivityCache cache) {
        active = false;
        CacheStats delta = cache.snapshotStats().minus(before);
        System.out.printf("CACHECOUNTS %d %d %d %d%n", configGets.sum(), stockGets.sum(),
                delta.hitCount(), delta.missCount());
    }
}
