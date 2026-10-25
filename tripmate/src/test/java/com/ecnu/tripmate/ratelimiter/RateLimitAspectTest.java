package com.ecnu.tripmate.ratelimiter;

import com.ecnu.tripmate.controller.*;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.exception.GlobalExceptionHandler;
import com.ecnu.tripmate.model.dto.Itinerary.ItineraryAddRequest;
import com.ecnu.tripmate.model.dto.Itinerary.ItineraryUpdateRequest;
import com.ecnu.tripmate.model.dto.tripMessage.TripMessageAddAiRequest;
import com.ecnu.tripmate.model.dto.user.UserLoginRequest;
import com.ecnu.tripmate.model.dto.user.UserRegisterRequest;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.ratelimiter.annotation.RateLimit;
import com.ecnu.tripmate.ratelimiter.aspect.RateLimitAspect;
import com.ecnu.tripmate.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class RateLimitAspectTest {
    private RateLimitAspect aspect;
    private RedissonClient redis;
    private UserService users;
    private ItineraryService itineraries;
    private TripMessageService messages;
    private ItineraryController itinerary;
    private TripMessageController message;
    private CommonController common;
    private UserController userController;
    private MockHttpServletRequest request;
    private final Map<String, RRateLimiter> limiters = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        aspect = new RateLimitAspect();
        redis = mock(RedissonClient.class);
        users = mock(UserService.class);
        itineraries = mock(ItineraryService.class);
        messages = mock(TripMessageService.class);
        ReflectionTestUtils.setField(aspect, "redissonClient", redis);
        ReflectionTestUtils.setField(aspect, "userService", users);
        // 模拟 Redis 按 key 共享的原子额度；trySetRate 重复调用不能重置次数。
        when(redis.getRateLimiter(anyString())).thenAnswer(invocation ->
                limiters.computeIfAbsent(invocation.getArgument(0), key -> newLimiter()));
        itinerary = proxy(new ItineraryController(), "itineraryService", itineraries);
        message = proxy(new TripMessageController(), "tripMessageService", messages);
        common = proxy(new CommonController(), "imageUploadService", mock(ImageUploadService.class));
        userController = proxy(new UserController(), "userService", users);
        request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        loginAs(1L);
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void userEndpointsHaveIndependentQuotasAndRejectOnlyTheNextRequest() {
        exhaust(1, () -> itinerary.add(new ItineraryAddRequest(), request));
        exhaust(5, () -> itinerary.update(new ItineraryUpdateRequest(), request));
        exhaust(20, () -> message.addAi(new TripMessageAddAiRequest(), request));
        exhaust(5, () -> common.upload(null));
        assertEquals(4, limiters.size());
        verify(itineraries, times(1)).add(any(), any(), any());
        verify(itineraries, times(5)).update(any(), any(), any());
        verify(messages, times(20)).addAi(any(), any(), any());
        for (var entry : limiters.entrySet()) {
            Duration window = entry.getKey().contains("CommonController")
                    ? Duration.ofDays(1) : Duration.ofDays(30);
            verify(entry.getValue(), atLeastOnce()).trySetRate(eq(RateType.OVERALL), anyLong(), eq(window), eq(window));
        }
    }

    @Test
    void anotherUserOnTheSameIpHasTheirOwnQuota() {
        exhaust(1, () -> itinerary.add(new ItineraryAddRequest(), request));
        loginAs(2L);
        exhaust(1, () -> itinerary.add(new ItineraryAddRequest(), request));
        assertEquals(2, limiters.size());
    }

    @Test
    void loginIsPerIpAndRegistrationIsSharedAcrossIps() {
        exhaust(100, () -> userController.login(new UserLoginRequest(), request));
        exhaust(30, () -> userController.register(new UserRegisterRequest()));
        request.setRemoteAddr("192.0.2.2");
        exhaust(100, () -> userController.login(new UserLoginRequest(), request));
        assertLimited(() -> userController.register(new UserRegisterRequest()));
        assertEquals(3, limiters.size());
        verify(users, never()).getLoginUser(any());
    }

    @Test
    void unauthenticatedUserDoesNotConsumeAnIpOrUserQuota() {
        when(users.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> itinerary.add(new ItineraryAddRequest(), request));
        assertEquals(40100, exception.getCode());
        RequestContextHolder.resetRequestAttributes();
        assertEquals(40100, assertThrows(BusinessException.class,
                () -> itinerary.add(new ItineraryAddRequest(), request)).getCode());
        verifyNoInteractions(redis, itineraries);
    }

    @Test
    void aShortTtlCannotResetMonthlyQuotaEarly() {
        var endpoint = proxy(new MonthlyEndpoint(), null, null);
        exhaust(1, endpoint::call);
        verify(limiters.values().iterator().next(), times(2)).trySetRate(
                RateType.OVERALL, 1, Duration.ofDays(30), Duration.ofDays(30));
    }

    @Test
    void newControllerInstanceReusesQuotaAndClearedCacheInitializesItAgain() {
        exhaust(1, () -> itinerary.add(new ItineraryAddRequest(), request));
        var anotherController = proxy(new ItineraryController(), "itineraryService", itineraries);
        assertLimited(() -> anotherController.add(new ItineraryAddRequest(), request));
        assertEquals(1, limiters.size());
        String key = limiters.keySet().iterator().next();
        assertTrue(key.startsWith("rate_limit:"));
        assertTrue(key.contains("ItineraryController.add("));
        assertTrue(key.endsWith(":user:1"));
        assertFalse(key.contains(":v2:"));
        assertFalse(key.contains(":rule:"));

        // 模拟显式清理限流缓存；下一次请求重新初始化注解指定的额度。
        limiters.clear();
        exhaust(1, () -> anotherController.add(new ItineraryAddRequest(), request));
        assertEquals(1, limiters.size());
        assertTrue(limiters.containsKey(key));
    }

    @Test
    void concurrentRequestsCannotAllPassTheSingleRequestQuota() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 20).mapToObj(i -> executor.submit(() -> {
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                try {
                    itinerary.add(new ItineraryAddRequest(), request);
                    return 1;
                } catch (BusinessException e) {
                    assertEquals(42900, e.getCode());
                    return 0;
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            })).toList();
            int accepted = 0;
            for (var future : futures) {
                accepted += future.get(10, TimeUnit.SECONDS);
            }
            assertEquals(1, accepted);
            verify(itineraries, times(1)).add(any(), any(), any());
        }
    }

    @Test
    void httpResponseContainsQuotaErrorAndDoesNotInvokeBusinessAgain() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(itinerary)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/itinerary/add").contentType("application/json").content("{\"tripId\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/itinerary/add").contentType("application/json").content("{\"tripId\":1}"))
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").value("30天内添加行程次数已达上限（1次），请稍后再试"));
        verify(itineraries, times(1)).add(any(), any(), any());
    }

    private void loginAs(long id) {
        User user = new User();
        user.setId(id);
        when(users.getLoginUser(any())).thenReturn(user);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target, String serviceField, Object service) {
        if (!(target instanceof CommonController) && !(target instanceof MonthlyEndpoint)) {
            ReflectionTestUtils.setField(target, "userService", users);
        }
        if (serviceField != null) {
            ReflectionTestUtils.setField(target, serviceField, service);
        }
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }

    private RRateLimiter newLimiter() {
        RRateLimiter limiter = mock(RRateLimiter.class);
        AtomicLong remaining = new AtomicLong(-1);
        when(limiter.trySetRate(any(RateType.class), anyLong(), any(Duration.class), any(Duration.class)))
                .thenAnswer(i -> remaining.compareAndSet(-1, i.getArgument(1, Long.class)));
        when(limiter.tryAcquire(1)).thenAnswer(i -> remaining.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0);
        return limiter;
    }

    private static void exhaust(int quota, Runnable request) {
        for (int i = 0; i < quota; i++) {
            assertDoesNotThrow(request::run);
        }
        assertLimited(request);
    }

    private static void assertLimited(Runnable request) {
        assertEquals(42900, assertThrows(BusinessException.class, request::run).getCode());
    }

    static class MonthlyEndpoint {
        @RateLimit(rate = 1, rateInterval = 2592000)
        public void call() { }
    }
}
