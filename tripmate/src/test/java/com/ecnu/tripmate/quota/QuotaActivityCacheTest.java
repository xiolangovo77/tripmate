package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.cache.quota.ActivityCache;
import com.ecnu.tripmate.cache.quota.ActivityRedis;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.quota.model.ActivityConfig;
import com.ecnu.tripmate.quota.model.ActivityDetail;
import com.ecnu.tripmate.service.QuotaActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotaActivityCacheTest {
    private final QuotaActivityMapper mapper = mock(QuotaActivityMapper.class);
    private final ActivityRedis redis = mock(ActivityRedis.class);
    private final ActivityCache cache = new ActivityCache(mapper, redis, true, 1024);
    private final ActivityConfig first = config(1);
    private final ActivityConfig second = config(2);

    private ActivityConfig config(long version) {
        return ActivityConfig.builder().id(42).status("published").configVersion(version).build();
    }

    @Test
    void tenConcurrentDetailsShareConfigLoadButReadStockTenTimes() throws Exception {
        var start = new CyclicBarrier(10);
        var service = new QuotaActivityService(mapper, cache, redis, mock(ApplicationEventPublisher.class));
        when(redis.read(42)).thenReturn(first);
        when(redis.remaining(42)).thenReturn(8L);
        try (var pool = Executors.newFixedThreadPool(10)) {
            var results = new ArrayList<Future<ActivityDetail>>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await(10, SECONDS);
                    return service.detail(42);
                }));
            }
            for (var result : results) {
                assertEquals(new ActivityDetail(first, 8L, true), result.get(10, SECONDS));
            }
        }
        verify(redis).read(42);
        verify(redis, times(10)).remaining(42);
        verifyNoInteractions(mapper);
    }

    @Test
    void redisMissLoadsDatabaseAndReusesFilledLocalSnapshot() {
        when(mapper.find(42, false)).thenReturn(first);
        when(redis.store(first)).thenReturn(true);
        assertEquals(first, cache.get(42));
        assertEquals(first, cache.get(42));
        assertEquals(1, cache.snapshotStats().missCount());
        assertEquals(1, cache.snapshotStats().hitCount());
        verify(mapper).find(42, false);
        verify(redis).store(first);
    }

    @Test
    void disablingLocalCacheReadsRedisForEachRequest() {
        var redisOnly = new ActivityCache(mapper, redis, false, 1024);
        when(redis.read(42)).thenReturn(first, second);
        assertEquals(first, redisOnly.get(42));
        assertEquals(second, redisOnly.get(42));
        assertEquals(0, redisOnly.snapshotStats().requestCount());
        verify(redis, times(2)).read(42);
    }

    @Test
    void invalidateDuringLoadPreventsLateOldResultBecomingCurrent() throws Exception {
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(redis.read(42)).thenAnswer(call -> {
            reading.countDown();
            assertTrue(release.await(10, SECONDS));
            return first;
        }).thenReturn(second);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var oldRead = pool.submit(() -> cache.get(42));
            try {
                assertTrue(reading.await(10, SECONDS));
                cache.invalidate(42, 2);
            } finally {
                release.countDown();
            }
            assertEquals(second, oldRead.get(10, SECONDS));
        }
        assertEquals(second, cache.get(42));
        verify(redis, times(2)).read(42);
    }

    @Test
    void staleSharedSnapshotAndRejectedRefillReloadDatabase() {
        cache.invalidate(42, 2);
        var third = config(3);
        when(redis.read(42)).thenReturn(first);
        when(mapper.find(42, false)).thenReturn(second, third);
        when(redis.store(third)).thenReturn(true);
        assertEquals(third, cache.get(42));
        verify(redis).store(second);
        verify(redis).store(third);
    }

    @Test
    void publicationWarmsBothCachesAndOlderNotificationCannotEvictIt() {
        cache.invalidate(42, 2);
        when(redis.store(second)).thenReturn(true);
        cache.prewarm(second);
        cache.invalidate(42, 1);
        assertEquals(second, cache.get(42));
        verify(redis).store(second);
        verify(redis, never()).read(42);
        verifyNoInteractions(mapper);
    }
}
