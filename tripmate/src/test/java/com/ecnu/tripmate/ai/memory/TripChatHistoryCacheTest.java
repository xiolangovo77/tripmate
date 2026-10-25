package com.ecnu.tripmate.ai.memory;

import cn.hutool.json.JSONUtil;
import com.ecnu.tripmate.cache.TripChatHistoryCache;
import com.ecnu.tripmate.cache.TripChatHistoryLoader;
import com.ecnu.tripmate.model.dto.ai.TripChatTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripChatHistoryCacheTest {
    private TripChatHistoryCache cache;
    private TripChatHistoryLoader loader;
    private RedissonClient redis;
    private RBucket<String> bucket;
    private AtomicReference<String> stored;
    private TransactionTemplate transaction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        cache = new TripChatHistoryCache();
        loader = mock(TripChatHistoryLoader.class);
        redis = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        stored = new AtomicReference<>();
        when(redis.<String>getBucket("tripmate:chat:turns:v1:7", StringCodec.INSTANCE)).thenReturn(bucket);
        when(redis.getLock(anyString())).thenReturn(mock(RLock.class));
        when(bucket.get()).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> { stored.set(invocation.getArgument(0)); return null; })
                .when(bucket).set(anyString(), eq(Duration.ofSeconds(3600)));
        when(loader.loadRecent(7L)).thenReturn(List.of());
        ReflectionTestUtils.setField(cache, "redissonClient", redis);
        ReflectionTestUtils.setField(cache, "loader", loader);
        ReflectionTestUtils.setField(cache, "ttl", 3600L);
        transaction = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        });
    }

    @Test
    void cacheMissRestoresPurePairsAndUsesANewNamespace() {
        when(loader.loadRecent(7L)).thenReturn(List.of(turn(1)));
        assertEquals(List.of(turn(1)), cache.getRecent(7L));
        assertEquals(List.of(turn(1)), cache.getRecent(7L));
        verify(loader, times(1)).loadRecent(7L);
        var json = JSONUtil.parseArray(stored.get()).getJSONObject(0);
        assertEquals(6, json.size());
        assertEquals("问题1", json.getStr("question"));
        verify(redis, times(2)).getBucket("tripmate:chat:turns:v1:7", StringCodec.INSTANCE);
    }

    @Test
    void onlyAfterCommitAppendsAndTrimsWholePairsToTen() {
        stored.set(JSONUtil.toJsonStr(LongStream.rangeClosed(1, 10).mapToObj(this::turn).toList()));
        transaction.executeWithoutResult(status -> {
            cache.appendAfterCommit(7L, turn(11));
            assertEquals(1L, storedTurns().getFirst().getReplyId());
            verify(bucket, never()).set(anyString(), any(Duration.class));
        });
        assertEquals(10, storedTurns().size());
        assertEquals(2L, storedTurns().getFirst().getReplyId());
        assertEquals(11L, storedTurns().getLast().getReplyId());
        assertEquals("问题11", storedTurns().getLast().getQuestion());
    }

    @Test
    void rollbackDoesNotReadOrWriteRedis() {
        transaction.executeWithoutResult(status -> {
            cache.appendAfterCommit(7L, turn(11));
            status.setRollbackOnly();
        });
        verifyNoInteractions(redis, bucket, loader);
    }

    @Test
    void expiredCacheRestoresCommittedPairWithoutDuplicatingIt() {
        when(loader.loadRecent(7L)).thenReturn(List.of(turn(10), turn(11)));
        transaction.executeWithoutResult(status -> cache.appendAfterCommit(7L, turn(11)));
        assertEquals(List.of(turn(10), turn(11)), storedTurns());
    }

    @Test
    void outOfOrderCommitCallbacksKeepBothPairsInOrder() {
        stored.set("[]");
        transaction.executeWithoutResult(status -> cache.appendAfterCommit(7L, turn(12)));
        transaction.executeWithoutResult(status -> cache.appendAfterCommit(7L, turn(11)));
        assertEquals(List.of(turn(11), turn(12)), storedTurns());
    }

    @Test
    void redisFailureFallsBackToDatabaseAndDoesNotFailCommittedReply() {
        when(redis.getLock(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        when(loader.loadRecent(7L)).thenReturn(List.of(turn(1)));
        assertEquals(List.of(turn(1)), cache.getRecent(7L));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(
                status -> cache.appendAfterCommit(7L, turn(2))));
    }

    @Test
    void requiresASavingTransaction() {
        assertThrows(IllegalStateException.class, () -> cache.appendAfterCommit(7L, turn(1)));
        verifyNoInteractions(redis);
    }

    private List<TripChatTurn> storedTurns() {
        return JSONUtil.toList(stored.get(), TripChatTurn.class);
    }

    private TripChatTurn turn(long id) {
        return new TripChatTurn(id, 100L, "小王", "问题" + id, "回答" + id, "none");
    }
}
