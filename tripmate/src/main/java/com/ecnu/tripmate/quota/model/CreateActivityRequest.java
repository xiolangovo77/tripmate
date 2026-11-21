package com.ecnu.tripmate.quota.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建活动草稿的请求。
 */
@Data
public class CreateActivityRequest {
    /**
     * 保存草稿时由数据库生成并回填的活动ID
     */
    @JsonIgnore
    private Long id;

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
    private Long totalStock;

    /**
     * 每份发放的 AI 助手使用次数
     */
    private Long grantAmount;
}
