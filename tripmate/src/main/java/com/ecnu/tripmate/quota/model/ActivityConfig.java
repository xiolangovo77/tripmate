package com.ecnu.tripmate.quota.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;

/**
 * 活动配置快照，库存余量独立读取。
 */
@Value
@Builder
@Jacksonized
public class ActivityConfig {
    /**
     * 活动ID
     */
    private long id;

    /**
     * 活动名称
     */
    private String name;

    /**
     * 活动详情展示的领取规则
     */
    private String ruleText;

    /**
     * 可开始领取的时间（含）
     */
    private LocalDateTime startTime;

    /**
     * 停止领取的时间（不含）
     */
    private LocalDateTime endTime;

    /**
     * 活动总份数
     */
    private long totalStock;

    /**
     * 每份发放的 AI 助手使用次数
     */
    private long grantAmount;

    /**
     * 发布状态：draft 为草稿，published 为已发布
     */
    private String status;

    /**
     * 配置版本，发布或更新展示信息时递增
     */
    private long configVersion;

    public boolean published() {
        return "published".equals(status);
    }
}
