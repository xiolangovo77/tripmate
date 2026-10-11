package com.ecnu.tripmate.ai.model;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Time;

@Data
public class ItineraryItemResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 第几天，从1开始
     */
    private Integer dayNumber;

    /**
     * 当天展示顺序，从1开始
     */
    private Integer sortOrder;

    /**
     * 开始时间
     */
    private Time startTime;

    /**
     * 结束时间，可为空
     */
    private Time endTime;

    /**
     * 景点或活动名称
     */
    private String activityName;

    /**
     * 活动类型：景点、餐饮、交通、住宿、自由活动、其他
     */
    private String activityType;

    /**
     * 地点名称
     */
    private String locationName;

    /**
     * 详细地址
     */
    private String address;

    /**
     * 活动安排说明
     */
    private String description;

    /**
     * 本项人均预计费用，NULL表示待确认，0表示免费
     */
    private BigDecimal estimatedCostPerPerson;
}
