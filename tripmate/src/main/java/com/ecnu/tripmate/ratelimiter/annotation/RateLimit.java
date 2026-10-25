package com.ecnu.tripmate.ratelimiter.annotation;

import com.ecnu.tripmate.ratelimiter.enums.RateLimitType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    
    /**
     * 限流key附加前缀；不同接口始终分别统计
     */
    String key() default "";
    
    /**
     * 每个时间窗口允许进入方法的请求数（方法执行失败也计数）
     */
    int rate() default 10;
    
    /**
     * 滚动时间窗口（秒），2592000 表示连续30天，不是自然月
     */
    int rateInterval() default 1;

    /**
     * 限流器空闲过期时间（小时），实际保留时间不短于时间窗口
     */
    int rateLimiterTtl() default 1;
    
    /**
     * 限流类型
     */
    RateLimitType limitType() default RateLimitType.USER;
    
    /**
     * 限流提示信息
     */
    String message() default "请求过于频繁，请稍后再试";
}
