package com.ecnu.tripmate.quota.model;

import lombok.Data;

/**
 * 更新活动名称和展示规则的请求。
 */
@Data
public class UpdateActivityDisplayRequest {
    /**
     * 活动名称
     */
    private String name;

    /**
     * 活动详情展示的领取规则
     */
    private String ruleText;
}
