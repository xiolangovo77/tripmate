package com.ecnu.tripmate.quota.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 同一活动的一代本地快照，以对象身份区分并发失效前后的加载。
 */
@Getter
@RequiredArgsConstructor
public final class ActivityCacheGeneration {
    /**
     * 当前缓存代接受的最低配置版本
     */
    private final long minimumVersion;
}
