package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.controller.QuotaActivityController;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.quota.model.*;
import com.ecnu.tripmate.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotaActivityServiceTest {
    private final QuotaActivityMapper mapper = mock(QuotaActivityMapper.class);
    private final ActivityCache cache = mock(ActivityCache.class);
    private final ActivityRedis redis = mock(ActivityRedis.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final QuotaActivityService service = new QuotaActivityService(mapper, cache, redis, events);
    private final ActivityConfig draft = ActivityConfig.builder().id(42).status("draft").configVersion(1).build();
    private final ActivityConfig published = ActivityConfig.builder().id(42).status("published").configVersion(2).build();

    private CreateActivityRequest validDraft() {
        var request = new CreateActivityRequest();
        request.setName("会员活动");
        request.setRuleText("每人一份");
        request.setStartTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        request.setEndTime(request.getStartTime().plusDays(1));
        request.setTotalStock(100L);
        request.setGrantAmount(5L);
        return request;
    }

    @Test
    void createReturnsIdAssignedByMapper() {
        var request = validDraft();
        doAnswer(call -> { request.setId(42L); return null; }).when(mapper).create(request);
        assertEquals(42, service.create(request));
    }

    @Test
    void invalidDraftIsRejectedBeforeInsertion() {
        var request = validDraft();
        request.setEndTime(request.getStartTime());
        assertThrows(BusinessException.class, () -> service.create(request));
        var invalidQuantity = validDraft();
        invalidQuantity.setGrantAmount(0L);
        assertThrows(BusinessException.class, () -> service.create(invalidQuantity));
        verifyNoInteractions(mapper);
    }

    @Test
    void detailDistinguishesAvailableSoldOutAndMissingStock() {
        when(cache.get(42)).thenReturn(published);
        when(redis.remaining(42)).thenReturn(12L, 0L, null);
        assertEquals(new ActivityDetail(published, 12L, true), service.detail(42));
        assertEquals(new ActivityDetail(published, 0L, true), service.detail(42));
        assertEquals(new ActivityDetail(published, null, false), service.detail(42));
        verify(redis, never()).prepareStock(any());
    }

    @Test
    void draftCannotBeReadOrRecovered() {
        when(cache.get(42)).thenReturn(draft);
        when(mapper.find(42, true)).thenReturn(draft);
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.detail(42)).getCode());
        assertThrows(BusinessException.class, () -> service.recoverStock(42));
        verifyNoInteractions(redis);
    }

    @Test
    void publishPreparesStockAndRequestsWarmupOnlyOnce() {
        when(mapper.find(42, true)).thenReturn(draft, published);
        when(mapper.find(42, false)).thenReturn(published);
        service.publish(42);
        service.publish(42);
        var order = inOrder(redis, mapper, events);
        order.verify(mapper).find(42, true);
        order.verify(redis).prepareStock(draft);
        order.verify(mapper).publish(42);
        order.verify(events).publishEvent(new ActivityConfigChangedEvent(published, true));
        order.verify(mapper).find(42, true);
        verifyNoMoreInteractions(redis, events);
        verify(mapper, times(1)).publish(42);
    }

    @Test
    void failedStockPreparationDoesNotPublishActivity() {
        when(mapper.find(42, true)).thenReturn(draft);
        doThrow(new IllegalStateException("Redis offline")).when(redis).prepareStock(draft);
        assertThrows(IllegalStateException.class, () -> service.publish(42));
        verify(mapper, never()).publish(anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void updateDisplaySendsNewVersionAndLeavesStockAlone() {
        var request = new UpdateActivityDisplayRequest();
        request.setName("新名称");
        request.setRuleText("新文案");
        var updated = ActivityConfig.builder().id(42).configVersion(3).status("published").build();
        when(mapper.find(42, false)).thenReturn(updated);
        service.updateDisplay(42, request);
        verify(mapper).updateDisplay(42, "新名称", "新文案");
        verify(events).publishEvent(new ActivityConfigChangedEvent(updated, false));
        verifyNoInteractions(redis);
    }

    @Test
    void existingStockIncludingZeroIsNeverRebuilt() {
        when(mapper.find(42, true)).thenReturn(published);
        when(redis.remaining(42)).thenReturn(0L);
        assertEquals(0, service.recoverStock(42));
        verify(mapper, never()).claims(anyLong());
        verify(redis, never()).recover(any(), anyList());
    }

    @Test
    void missingStockRequiresReconciliation() {
        var claim = new ActivityClaim();
        claim.setUserId(9);
        claim.setClaimNo("C9");
        var claims = List.of(claim);
        when(mapper.find(42, true)).thenReturn(published);
        when(redis.remaining(42)).thenReturn(null);
        when(mapper.claims(42)).thenReturn(claims);
        when(redis.recover(published, claims)).thenReturn(99L);
        assertEquals(99, service.recoverStock(42));
        verify(redis, never()).prepareStock(any());
    }

    @Test
    void recoveryRequiresCurrentAdminRole() {
        var users = mock(UserService.class);
        var operations = mock(QuotaActivityService.class);
        var controller = new QuotaActivityController(operations, users);
        var request = new MockHttpServletRequest();
        var sessionUser = User.builder().id(9L).userRole("admin").build();
        when(users.getLoginUser(request)).thenReturn(sessionUser);
        when(users.getById(9L)).thenReturn(User.builder().id(9L).userRole("user").build(), sessionUser);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> controller.recoverStock(42, request)).getCode());
        verifyNoInteractions(operations);
        when(operations.recoverStock(42)).thenReturn(70L);
        assertEquals(70L, controller.recoverStock(42, request).getData());
    }
}
