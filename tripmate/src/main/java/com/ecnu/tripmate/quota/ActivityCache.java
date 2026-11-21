package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.quota.model.ActivityCacheGeneration;
import com.ecnu.tripmate.quota.model.ActivityConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ActivityCache {
    /**
     * 从 MySQL 加载活动配置。
     */
    private final QuotaActivityMapper quotaActivityMapper;

    /**
     * 读取共享配置缓存，并按配置版本回填。
     */
    private final ActivityRedis activityRedis;

    /**
     * 是否启用本实例的 Caffeine 缓存。
     */
    private final boolean localEnabled;

    /**
     * 按活动 ID 保存当前缓存代及其接受的最低配置版本。
     */
    private final Cache<Long, ActivityCacheGeneration> generations;

    /**
     * 按缓存代保存配置快照，同一代的并发请求共享加载结果。
     */
    private final Cache<ActivityCacheGeneration, ActivityConfig> snapshots;

    public ActivityCache(QuotaActivityMapper quotaActivityMapper, ActivityRedis activityRedis,
                         @Value("${quota.cache.local-enabled:true}") boolean localEnabled,
                         @Value("${quota.cache.maximum-size:1024}") long maximumSize) {
        this.quotaActivityMapper = quotaActivityMapper;
        this.activityRedis = activityRedis;
        this.localEnabled = localEnabled;
        if (localEnabled && maximumSize < 1) {
            throw new IllegalArgumentException("活动本地缓存容量必须为正数");
        }
        generations = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterAccess(Duration.ofMinutes(5)).build();
        snapshots = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(Duration.ofSeconds(30))
                .recordStats().build();
    }

    /** 活动配置快照的累计统计，用于按采样窗口计算 L1 命中率。 */
    CacheStats snapshotStats() {
        return snapshots.stats();
    }

    public ActivityConfig get(long id) {
        //1、关闭本地缓存时直接读取 Redis，未命中再查数据库。
        if (!localEnabled) {
            return load(id, 0);
        }
        while (true) {
            //2、按活动当前缓存代合并同实例的并发加载。
            ActivityCacheGeneration generation = generations.get(id, ignored -> new ActivityCacheGeneration(0));
            ActivityConfig config = snapshots.get(generation, key -> load(id, key.getMinimumVersion()));
            //3、加载期间若收到失效通知，按新一代重新读取。
            if (generations.getIfPresent(id) == generation) {
                return config;
            }
        }
    }

    private ActivityConfig load(long id, long minimumVersion) {
        //1、检查 Redis 快照是否达到本机已知的最低版本。
        ActivityConfig config = activityRedis.read(id);
        if (config != null && config.getConfigVersion() >= minimumVersion) {
            return config;
        }
        //2、回源查询并按版本回填；遇到并发更新时重新查询。
        for (int attempt = 0; attempt < 3; attempt++) {
            config = quotaActivityMapper.find(id, false);
            if (config.getConfigVersion() >= minimumVersion && activityRedis.store(config)) {
                return config;
            }
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "活动配置正在更新，请重试");
    }

    public void invalidate(long id, long version) {
        if (localEnabled) {
            generations.asMap().compute(id, (key, old) ->
                    old != null && old.getMinimumVersion() >= version ? old : new ActivityCacheGeneration(version));
        }
    }

    public void prewarm(ActivityConfig config) {
        //1、先按版本写入共享缓存。
        if (activityRedis.store(config) && localEnabled) {
            //2、仅向接受该版本的本地缓存代写入快照。
            ActivityCacheGeneration generation = generations.getIfPresent(config.getId());
            if (generation != null && config.getConfigVersion() >= generation.getMinimumVersion()) {
                snapshots.asMap().putIfAbsent(generation, config);
            }
        }
    }
}
