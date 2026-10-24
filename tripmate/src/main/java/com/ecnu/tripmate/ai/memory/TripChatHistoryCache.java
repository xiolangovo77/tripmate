package com.ecnu.tripmate.ai.memory;

import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 使用 Redis 缓存历史记忆，Redis 仅存纯问答，独立 key 不读取旧 LangChain4j 混合记忆。 */
@Service
@Slf4j
public class TripChatHistoryCache {
    static final String KEY_PREFIX = "tripmate:chat:turns:v1:";

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private TripChatHistoryLoader loader;

    @Value("${spring.data.redis.ttl}")
    private long ttl;

    public List<TripChatTurn> getRecent(Long tripId) {
        try {
            RLock lock = redissonClient.getLock(KEY_PREFIX + tripId + ":lock");
            lock.lock();
            try {
                RBucket<String> bucket = bucket(tripId);
                String cached = bucket.get();
                if (cached != null) {
                    return decode(cached);
                }
                List<TripChatTurn> turns = loader.loadRecent(tripId);
                write(bucket, turns);
                return turns;
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException exception) {
            log.warn("读取聊天缓存失败，改从数据库恢复，tripId={}", tripId, exception);
            return loader.loadRecent(tripId);
        }
    }

    /** 回滚不写入；Redis 故障不将已经提交的 AI 回复变成业务失败。 */
    public void appendAfterCommit(Long tripId, TripChatTurn turn) {
        // 判断当前线程是否存在实际事务、当前线程的事务同步机制是否激活
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("聊天记忆必须在保存 AI 回复的事务内注册");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                appendCommitted(tripId, turn);
            }
        });
    }

    private void appendCommitted(Long tripId, TripChatTurn turn) {
        try {
            RLock lock = redissonClient.getLock(KEY_PREFIX + tripId + ":lock");
            lock.lock();
            try {
                RBucket<String> bucket = bucket(tripId);
                try {
                    String cached = bucket.get();
                    var turns = new ArrayList<>(cached == null ? loader.loadRecent(tripId) : decode(cached));
                    // 让缓存更新具备一定的幂等性：同一个回复重复执行缓存追加逻辑，也不会因此产生重复记录。
                    turns.removeIf(existing -> existing.getReplyId().equals(turn.getReplyId()));
                    turns.add(turn);
                    // 雪花 ID 在 AI 回复保存时生成，避免并发提交回调颠倒问答顺序。
                    turns.sort(Comparator.comparing(TripChatTurn::getReplyId));
                    int from = Math.max(0, turns.size() - TripChatHistoryLoader.HISTORY_PAIR_LIMIT);
                    write(bucket, turns.subList(from, turns.size()));
                } catch (RuntimeException exception) {
                    // 尽量失效缓存，下次由数据库恢复，避免继续读取不完整历史。
                    bucket.delete();
                    throw exception;
                }
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException exception) {
            log.warn("AI 回复已保存，聊天缓存更新失败，tripId={}", tripId, exception);
        }
    }

    private RBucket<String> bucket(Long tripId) {
        return redissonClient.getBucket(KEY_PREFIX + tripId, StringCodec.INSTANCE);
    }

    private List<TripChatTurn> decode(String value) {
        return JSONUtil.toList(value, TripChatTurn.class);
    }

    private void write(RBucket<String> bucket, List<TripChatTurn> turns) {
        bucket.set(JSONUtil.toJsonStr(turns), Duration.ofSeconds(ttl));
    }
}
