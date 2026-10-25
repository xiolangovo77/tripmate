package com.ecnu.tripmate.ratelimiter.aspect;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.ratelimiter.annotation.RateLimit;
import com.ecnu.tripmate.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private UserService userService;

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit) {
        if (rateLimit.rate() <= 0 || rateLimit.rateInterval() <= 0 || rateLimit.rateLimiterTtl() <= 0) {
            throw new IllegalArgumentException("限流次数、时间窗口和过期时间必须大于0");
        }
        String key = generateRateLimitKey(point, rateLimit);
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        Duration interval = Duration.ofSeconds(rateLimit.rateInterval());
        // 空闲过期时间不得短于统计窗口，否则额度会提前重置。
        Duration keepAlive = Duration.ofSeconds(Math.max(
                Duration.ofHours(rateLimit.rateLimiterTtl()).toSeconds(), rateLimit.rateInterval()));
        // Redisson 在获取令牌的 Lua 脚本中统一续期配置和计数，避免单独 expire 的竞争。
        rateLimiter.trySetRate(RateType.OVERALL, rateLimit.rate(), interval, keepAlive);
        // 尝试获取令牌，如果获取失败则限流
        if (!rateLimiter.tryAcquire(1)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
        }
    }

    private String generateRateLimitKey(JoinPoint point, RateLimit rateLimit) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append("rate_limit:");
        // 添加自定义前缀
        if (!rateLimit.key().isEmpty()) {
            keyBuilder.append(rateLimit.key()).append(":");
        }
        // 所有限流类型都必须隔离接口（包括同名类和重载方法）。
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        keyBuilder.append(method.toGenericString()).append(":");
        switch (rateLimit.limitType()) {
            case API:
                keyBuilder.append("api");
                break;
            case USER:
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes == null) {
                    throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
                }
                User loginUser = userService.getLoginUser(attributes.getRequest());
                if (loginUser == null || loginUser.getId() == null) {
                    throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
                }
                keyBuilder.append("user:").append(loginUser.getId());
                break;
            case IP:
                // IP级别：客户端IP
                keyBuilder.append("ip:").append(getClientIP());
                break;
            default:
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的限流类型");
        }
        return keyBuilder.toString();
    }

    private String getClientIP() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 处理多级代理的情况
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "unknown";
    }
}
