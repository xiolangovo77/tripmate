package com.ecnu.tripmate.cache;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.ecnu.tripmate.event.TripItineraryUpdatingEvent;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** 按旅行缓存一份完整行程；所有写入和缓存回填共用同一把旅行锁。 */
@Component
@Slf4j
public class ItineraryCache {
    private static final String KEY_PREFIX = "tripmate:itinerary:v1:";

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ItineraryCacheLoader loader;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Value("${spring.data.redis.ttl}")
    private long ttl;

    /** 只判断更新操作，不把生成、确认或缓存读取持有的旅行锁当成更新。 */
    public boolean isBusy(Long tripId) {
        return redissonClient.getLock(KEY_PREFIX + tripId + ":updating").isLocked();
    }

    /** 在旅行锁内标记更新状态，覆盖整个写事务及提交回调，失败时也清除并通知。 */
    public <T> T withUpdateStatus(Long tripId, Supplier<T> action) {
        // 独立的 Redis 锁仅用于共享更新状态；看门狗续期，进程退出后自动过期。
        RLock updating = redissonClient.getLock(KEY_PREFIX + tripId + ":updating");
        updating.lock();
        try {
            eventPublisher.publishEvent(new TripItineraryUpdatingEvent(tripId));
            return action.get();
        } finally {
            updating.unlock();
            eventPublisher.publishEvent(new TripItineraryUpdatingEvent(tripId));
        }
    }

    /** 不指定固定租期，使用 Redisson 看门狗为长时间 AI 调用续期。 */
    public <T> T withTripLock(Long tripId, Supplier<T> action) {
        RLock lock = lock(tripId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 优先读取缓存，未命中时由 loader 使用新事务加载最新已提交行程。 */
    public ItineraryVO get(Long tripId) {
        try {
            return withTripLock(tripId, () -> {
                RBucket<String> bucket = bucket(tripId);
                String cached = bucket.get();
                if (cached != null) {
                    try {
                        ItineraryVO snapshot = JSONUtil.toBean(cached, ItineraryVO.class);
                        if (snapshot.getId() != null && Objects.equals(tripId, snapshot.getTripId())
                                && snapshot.getItineraryItemVOList() != null
                                && !snapshot.getItineraryItemVOList().isEmpty()) {
                            return snapshot;
                        }
                    } catch (RuntimeException exception) {
                        log.warn("行程缓存格式异常，重新加载，tripId={}", tripId);
                    }
                    bucket.delete();
                }
                ItineraryVO snapshot = loader.loadLatest(tripId);
                if (snapshot != null) {
                    try {
                        bucket.set(serialize(snapshot), Duration.ofSeconds(ttl));
                    } catch (RedisException exception) {
                        log.warn("行程已读取，缓存回填失败，tripId={}", tripId, exception);
                    }
                }
                return snapshot;
            });
        } catch (RedisException exception) {
            // Redis 不可用时只读数据库、不回填；写操作必须成功取得锁才可执行。
            log.warn("行程缓存不可用，读取数据库，tripId={}", tripId, exception);
            return loader.loadLatest(tripId);
        }
    }

    /** 回滚不刷新；缓存故障不将已经提交的行程变成业务失败。 */
    public void refreshAfterCommit(Long tripId) {
        // 判断当前线程是否存在实际事务、事务同步是否激活，以及是否持有旅行锁。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !lock(tripId).isHeldByCurrentThread()) {
            throw new IllegalStateException("行程缓存更新必须在旅行锁保护的数据库事务中登记");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshCommitted(tripId);
            }
        });
    }

    private void refreshCommitted(Long tripId) {
        try {
            ItineraryVO snapshot = loader.loadLatest(tripId);
            RBucket<String> bucket = bucket(tripId);
            if (snapshot == null) {
                bucket.delete();
            } else {
                bucket.set(serialize(snapshot), Duration.ofSeconds(ttl));
            }
        } catch (RuntimeException exception) {
            // 数据库已经提交，不将缓存失败当作生成失败；尽量移除旧版本。
            try {
                bucket(tripId).delete();
            } catch (RuntimeException deletionFailure) {
                exception.addSuppressed(deletionFailure);
            }
            log.warn("行程已提交，缓存更新失败，tripId={}", tripId, exception);
        }
    }

    private String serialize(ItineraryVO snapshot) {
        return JSONUtil.toJsonStr(snapshot, JSONConfig.create().setStripTrailingZeros(false));
    }

    private RBucket<String> bucket(Long tripId) {
        return redissonClient.getBucket(KEY_PREFIX + tripId, StringCodec.INSTANCE);
    }

    private RLock lock(Long tripId) {
        return redissonClient.getLock(KEY_PREFIX + tripId + ":lock");
    }
}
