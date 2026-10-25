package com.ecnu.tripmate.service;

import com.ecnu.tripmate.ai.AiItineraryGeneratorService;
import com.ecnu.tripmate.ai.AiItineraryGeneratorServiceFactory;
import com.ecnu.tripmate.ai.model.ItineraryItemResult;
import com.ecnu.tripmate.ai.model.ItineraryResult;
import com.ecnu.tripmate.cache.ItineraryCache;
import com.ecnu.tripmate.cache.ItineraryCacheLoader;
import com.ecnu.tripmate.event.TripItineraryChangedEvent;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.mapper.ItineraryItemMapper;
import com.ecnu.tripmate.mapper.ItineraryMapper;
import com.ecnu.tripmate.model.entity.*;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.service.impl.ItineraryServiceImpl;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItineraryCacheServiceTest {
    private ItineraryServiceImpl service;
    private ItineraryCache cache;
    private ItineraryCacheLoader transactionalLoader;
    private ItineraryMapper mapper;
    private ItineraryItemMapper items;
    private TripMemberService members;
    private AiItineraryGeneratorService ai;
    private ApplicationEventPublisher publisher;
    private AtomicReference<Itinerary> row;
    private AtomicReference<ItineraryVO> refreshedSnapshot;
    private final User user = User.builder().id(100L).build();
    private List<String> events;
    private boolean locked;

    @BeforeEach
    void setup() {
        service = spy(new ItineraryServiceImpl());
        cache = mock(ItineraryCache.class);
        mapper = mock(ItineraryMapper.class);
        items = mock(ItineraryItemMapper.class);
        var itemService = mock(ItineraryItemService.class);
        var trips = mock(TripService.class);
        members = mock(TripMemberService.class);
        var factory = mock(AiItineraryGeneratorServiceFactory.class);
        ai = mock(AiItineraryGeneratorService.class);
        publisher = mock(ApplicationEventPublisher.class);
        events = new ArrayList<>();
        refreshedSnapshot = new AtomicReference<>();
        row = new AtomicReference<>(Itinerary.builder().id(8L).tripId(7L).title("旧行程").status("draft").build());
        doAnswer(i -> row.get()).when(service).getById(8L);
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenAnswer(i -> row.get());
        when(itemService.getMapper()).thenReturn(items);
        when(items.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(
                ItineraryItem.builder().id(10L).itineraryId(8L).dayNumber(1).sortOrder(1).activityName("博物馆").build()));
        when(items.deleteByQuery(any(QueryWrapper.class))).thenReturn(1);
        when(itemService.saveBatch(anyCollection())).thenReturn(true);
        doAnswer(i -> { row.set(null); return true; }).when(service).removeById(8L);
        doAnswer(i -> {
            Itinerary saved = i.getArgument(0);
            saved.setId(9L);
            row.set(saved);
            return true;
        }).when(service).save(any(Itinerary.class));
        when(mapper.update(any(Itinerary.class))).thenReturn(1);
        when(trips.isTripExists(7L)).thenReturn(Trip.builder().id(7L).ownerId(100L).travelDays(1).title("旅行").build());
        when(trips.getInitItineraryPrompt(7L)).thenReturn("最新旅行成员");
        when(factory.getAiItineraryGeneratorService(7L)).thenReturn(ai);
        var result = new ItineraryResult();
        var item = new ItineraryItemResult();
        item.setDayNumber(1);
        result.setTitle("新行程");
        result.setItineraryItemResultList(List.of(item));
        when(ai.generatorItinerary(anyString())).thenReturn(result);
        when(ai.updateItinerary(anyString())).thenReturn(result);
        when(cache.withTripLock(eq(7L), any())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            locked = true;
            events.add("lock");
            try { return ((Supplier<?>) i.getArgument(1)).get(); }
            finally { events.add("unlock"); locked = false; }
        });
        when(cache.get(7L)).thenAnswer(i -> transactionalLoader.loadLatest(7L));
        doAnswer(i -> {
            assertTrue(locked);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            events.add("schedule-cache");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                public void afterCommit() {
                    assertTrue(locked);
                    refreshedSnapshot.set(transactionalLoader.loadLatest(7L));
                }
            });
            return null;
        }).when(cache).refreshAfterCommit(7L);
        var txManager = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) { events.add("begin"); }
            protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
            protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
        };
        var loader = new ItineraryCacheLoader();
        ReflectionTestUtils.setField(loader, "itineraryMapper", mapper);
        ReflectionTestUtils.setField(loader, "itineraryItemMapper", items);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(txManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var loaderProxy = new ProxyFactory(loader);
        loaderProxy.addAdvice(interceptor);
        transactionalLoader = (ItineraryCacheLoader) loaderProxy.getProxy();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "itineraryCache", cache);
        ReflectionTestUtils.setField(service, "itineraryItemService", itemService);
        ReflectionTestUtils.setField(service, "tripService", trips);
        ReflectionTestUtils.setField(service, "tripMemberService", members);
        ReflectionTestUtils.setField(service, "aiItineraryGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(service, "eventPublisher", publisher);
        ReflectionTestUtils.setField(service, "transactionManager", txManager);
    }

    @Test
    void initializationSchedulesCompleteSnapshotInsideLockAndCommitsBeforeUnlock() {
        row.set(null);
        assertEquals(9L, service.add(7L, "生成行程", user));
        verify(cache).refreshAfterCommit(7L);
        assertEquals(9L, refreshedSnapshot.get().getId());
        assertEquals(1, refreshedSnapshot.get().getItineraryItemVOList().size());
        assertEquals(List.of("lock", "begin", "schedule-cache", "commit", "begin", "commit", "unlock"), events);
        verify(publisher).publishEvent(new TripItineraryChangedEvent(7L));
    }

    @Test
    void updateUsesCachedSnapshotAndPublishesOnlyTheFinalReplacement() {
        var snapshot = new ItineraryVO();
        snapshot.setId(8L);
        snapshot.setTripId(7L);
        snapshot.setTitle("缓存当前行程");
        snapshot.setItineraryItemVOList(List.of());
        doReturn(snapshot).when(cache).get(7L);
        assertEquals(9L, service.update(8L, List.of(), user));
        verify(ai).updateItinerary(argThat(p -> p.contains("缓存当前行程") && p.contains("最新旅行成员")));
        verify(cache).refreshAfterCommit(7L);
        assertEquals(9L, refreshedSnapshot.get().getId());
        verify(publisher, times(1)).publishEvent(new TripItineraryChangedEvent(7L));
        assertEquals(List.of("lock", "begin", "schedule-cache", "commit", "begin", "commit", "unlock"), events);
    }

    @Test
    void confirmationRefreshesCachedStatus() {
        assertEquals(1, service.confirmed(8L, user));
        verify(cache).refreshAfterCommit(7L);
        assertEquals("confirmed", refreshedSnapshot.get().getStatus());
        assertEquals(List.of("lock", "begin", "schedule-cache", "commit", "begin", "commit", "unlock"), events);
    }

    @Test
    void staleIdIsRecheckedAfterAcquiringLock() {
        when(mapper.selectOneByQuery(any(QueryWrapper.class)))
                .thenReturn(Itinerary.builder().id(9L).tripId(7L).build());
        var error = assertThrows(BusinessException.class, () -> service.update(8L, List.of(), user));
        assertTrue(error.getMessage().contains("行程已更新"));
        verifyNoInteractions(ai, items, publisher);
        verify(cache, never()).get(anyLong());
        assertEquals(List.of("lock", "begin", "rollback", "unlock"), events);
    }

    @Test
    void modelFailureRollsBackWithoutSchedulingCacheOrNotification() {
        row.set(null);
        when(ai.generatorItinerary(anyString())).thenThrow(new IllegalStateException("model failed"));
        assertThrows(IllegalStateException.class, () -> service.add(7L, "生成行程", user));
        verify(cache, never()).refreshAfterCommit(anyLong());
        verifyNoInteractions(publisher);
        assertEquals(List.of("lock", "begin", "rollback", "unlock"), events);
    }

    @Test
    void unauthorizedReaderCannotAccessCache() {
        doThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR)).when(members).checkMemberAccess(7L, 100L);
        assertThrows(BusinessException.class, () -> service.getByTripId(7L, user));
        verifyNoInteractions(cache, mapper, items);
    }

    @Test
    void authorizedCacheHitDoesNotQueryItineraryTables() {
        var snapshot = new ItineraryVO();
        snapshot.setId(8L);
        doReturn(snapshot).when(cache).get(7L);
        assertSame(snapshot, service.getByTripId(7L, user));
        verify(members).checkMemberAccess(7L, 100L);
        verifyNoInteractions(mapper, items);
    }

    @Test
    void cacheMissLoadsHeaderAndDetailsInOneReadOnlySnapshot() {
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return row.get();
        });
        var snapshot = service.getByTripId(7L, user);
        assertEquals(8L, snapshot.getId());
        assertEquals("博物馆", snapshot.getItineraryItemVOList().getFirst().getActivityName());
        assertEquals(List.of("begin", "commit"), events);
    }
}
