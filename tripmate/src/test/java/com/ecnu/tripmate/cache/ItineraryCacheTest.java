package com.ecnu.tripmate.cache;

import com.ecnu.tripmate.model.vo.itinerary.ItineraryItemVO;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItineraryCacheTest {
    private ItineraryCache cache;
    private ItineraryCacheLoader loader;
    private RedissonClient redis;
    private RBucket<String> bucket;
    private RLock lock;
    private AtomicReference<String> stored;
    private TransactionTemplate transaction;
    private List<String> events;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        cache = new ItineraryCache();
        loader = mock(ItineraryCacheLoader.class);
        when(loader.loadLatest(7L)).thenReturn(snapshot(10L));
        ReflectionTestUtils.setField(cache, "loader", loader);
        redis = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        lock = mock(RLock.class);
        var mutex = new ReentrantLock();
        doAnswer(i -> { mutex.lock(); return null; }).when(lock).lock();
        doAnswer(i -> { mutex.unlock(); return null; }).when(lock).unlock();
        when(lock.isHeldByCurrentThread()).thenAnswer(i -> mutex.isHeldByCurrentThread());
        when(redis.getLock("tripmate:itinerary:v1:7:lock")).thenReturn(lock);
        when(redis.<String>getBucket("tripmate:itinerary:v1:7", StringCodec.INSTANCE)).thenReturn(bucket);
        stored = new AtomicReference<>();
        events = new ArrayList<>();
        when(bucket.get()).thenAnswer(i -> stored.get());
        doAnswer(i -> { stored.set(i.getArgument(0)); events.add("cache"); return null; })
                .when(bucket).set(anyString(), eq(Duration.ofSeconds(3600)));
        when(bucket.delete()).thenAnswer(i -> stored.getAndSet(null) != null);
        ReflectionTestUtils.setField(cache, "redissonClient", redis);
        ReflectionTestUtils.setField(cache, "ttl", 3600L);
        transaction = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
            protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
        });
    }

    @Test
    void missAndExpiryReloadCompleteSnapshotIncludingTimeAndMoney() {
        var snapshot = snapshot(10L);
        assertEquals(snapshot, cache.get(7L));
        assertEquals(snapshot, cache.get(7L));
        verify(loader, times(1)).loadLatest(7L);
        stored.set(null);
        when(loader.loadLatest(7L)).thenReturn(snapshot(11L));
        assertEquals(snapshot(11L), cache.get(7L));
        verify(bucket, times(2)).set(anyString(), eq(Duration.ofSeconds(3600)));
    }

    @Test
    void commitUpdatesCacheBeforeNotificationWhileStillHoldingLock() {
        when(loader.loadLatest(7L)).thenAnswer(i -> {
            assertEquals(List.of("commit"), events);
            assertTrue(lock.isHeldByCurrentThread());
            events.add("load");
            return snapshot(11L);
        });
        cache.withTripLock(7L, () -> transaction.execute(status -> {
            cache.refreshAfterCommit(7L);
            assertNull(stored.get());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                public void afterCompletion(int completion) {
                    assertEquals(STATUS_COMMITTED, completion);
                    assertTrue(lock.isHeldByCurrentThread());
                    assertEquals(11L, cache.get(7L).getId());
                    events.add("notification");
                }
            });
            return null;
        }));
        assertEquals(List.of("commit", "load", "cache", "notification"), events);
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void rollbackPreservesPreviousSnapshot() {
        cache.get(7L);
        cache.withTripLock(7L, () -> transaction.execute(status -> {
            cache.refreshAfterCommit(7L);
            status.setRollbackOnly();
            return null;
        }));
        assertEquals(10L, cache.get(7L).getId());
        verify(loader, times(1)).loadLatest(7L);
        verify(bucket, times(1)).set(anyString(), any(Duration.class));
    }

    @Test
    void deletionInvalidatesOnlyAfterCommit() {
        cache.get(7L);
        when(loader.loadLatest(7L)).thenReturn(null);
        cache.withTripLock(7L, () -> transaction.execute(status -> {
            cache.refreshAfterCommit(7L);
            assertNotNull(stored.get());
            return null;
        }));
        assertNull(stored.get());
        assertNull(cache.get(7L));
    }

    @Test
    void failedCacheWriteAfterCommitDeletesOldValueWithoutFailingOperation() {
        cache.get(7L);
        when(loader.loadLatest(7L)).thenReturn(snapshot(11L));
        doThrow(new RedisException("write failed")).when(bucket).set(anyString(), any(Duration.class));
        assertDoesNotThrow(() -> cache.withTripLock(7L, () -> transaction.execute(status -> {
            cache.refreshAfterCommit(7L);
            return null;
        })));
        assertNull(stored.get());
        assertTrue(events.contains("commit"));
    }

    @Test
    void unavailableRedisAllowsDatabaseReadButNeverUnlockedMutation() {
        doThrow(new RedisException("unavailable")).when(lock).lock();
        assertEquals(snapshot(10L), cache.get(7L));
        assertThrows(RedisException.class, () -> cache.withTripLock(7L, () -> fail("不能无锁写入")));
        verifyNoInteractions(bucket);
    }

    @Test
    void failedReloadAfterCommitInvalidatesOldCacheWithoutFailingCommittedWrite() {
        cache.get(7L);
        when(loader.loadLatest(7L)).thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(() -> cache.withTripLock(7L, () -> transaction.execute(status -> {
            cache.refreshAfterCommit(7L);
            return null;
        })));
        assertNull(stored.get());
        assertTrue(events.contains("commit"));
    }

    @Test
    void registrationRequiresBothTransactionAndTripLock() {
        assertThrows(IllegalStateException.class, () -> cache.refreshAfterCommit(7L));
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            cache.refreshAfterCommit(7L);
            return null;
        }));
    }

    @Test
    void invalidCachedPayloadIsRebuilt() {
        stored.set("not-json");
        assertEquals(snapshot(10L), cache.get(7L));
        verify(bucket).delete();
    }

    @Test
    void concurrentFillCannotOverwriteNewerCommittedSnapshot() throws Exception {
        var loading = new CountDownLatch(1);
        var finishLoading = new CountDownLatch(1);
        var writerWaiting = new CountDownLatch(1);
        when(loader.loadLatest(7L)).thenAnswer(i -> {
            loading.countDown();
            await(finishLoading);
            return snapshot(10L);
        }).thenReturn(snapshot(11L));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reader = executor.submit(() -> cache.get(7L));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            var writer = executor.submit(() -> {
                writerWaiting.countDown();
                return cache.withTripLock(7L, () -> transaction.execute(status -> {
                    cache.refreshAfterCommit(7L);
                    return null;
                }));
            });
            try {
                assertTrue(writerWaiting.await(5, TimeUnit.SECONDS));
                assertFalse(writer.isDone());
            } finally {
                finishLoading.countDown();
            }
            assertEquals(10L, reader.get(5, TimeUnit.SECONDS).getId());
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(11L, cache.get(7L).getId());
        } finally {
            finishLoading.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static ItineraryVO snapshot(long id) {
        var item = new ItineraryItemVO();
        item.setId(100L);
        item.setDayNumber(1);
        item.setSortOrder(1);
        item.setStartTime(Time.valueOf("09:30:00"));
        item.setActivityName("游览博物馆");
        item.setEstimatedCostPerPerson(new BigDecimal("12.50"));
        var snapshot = new ItineraryVO();
        snapshot.setId(id);
        snapshot.setTripId(7L);
        snapshot.setStatus("draft");
        snapshot.setEstimatedCostPerPerson(new BigDecimal("100.00"));
        snapshot.setItineraryItemVOList(List.of(item));
        return snapshot;
    }
}
