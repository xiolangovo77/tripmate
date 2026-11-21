package com.ecnu.tripmate.quota.model;

import lombok.Value;

/**
 * 活动详情及本次查询读取的库存。
 */
@Value
public class ActivityDetail {
    /**
     * 活动配置快照
     */
    private ActivityConfig config;

    /**
     * Redis 中的实时剩余份数，库存键缺失时为 null
     */
    private Long remainingStock;

    /**
     * 是否读到库存值；库存为 0 时仍为 true
     */
    private boolean stockAvailable;
}
