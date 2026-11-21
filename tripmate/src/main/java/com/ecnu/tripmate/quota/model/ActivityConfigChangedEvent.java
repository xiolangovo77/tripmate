package com.ecnu.tripmate.quota.model;

import lombok.Value;

/**
 * 活动配置提交后用于同步缓存的事件。
 */
@Value
public class ActivityConfigChangedEvent {
    /**
     * 本次变更后的活动配置
     */
    private ActivityConfig config;

    /**
     * 提交后是否预热配置缓存
     */
    private boolean prewarm;
}
