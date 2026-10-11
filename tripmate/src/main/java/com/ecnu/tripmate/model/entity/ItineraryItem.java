package com.ecnu.tripmate.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.time.LocalDateTime;

import java.io.Serial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行程明细表 实体类。
 *
 * @author Wolfcub
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("itinerary_item")
public class ItineraryItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 行程明细ID
     */
    @Id(keyType = KeyType.Auto)
    private BigInteger id;

    /**
     * 所属行程ID
     */
    @Column("itineraryId")
    private BigInteger itineraryId;

    /**
     * 第几天，从1开始
     */
    @Column("dayNumber")
    private Integer dayNumber;

    /**
     * 当天展示顺序，从1开始
     */
    @Column("sortOrder")
    private Integer sortOrder;

    /**
     * 开始时间
     */
    @Column("startTime")
    private Time startTime;

    /**
     * 结束时间，可为空
     */
    @Column("endTime")
    private Time endTime;

    /**
     * 景点或活动名称
     */
    @Column("activityName")
    private String activityName;

    /**
     * 活动类型：景点、餐饮、交通、住宿、自由活动、其他
     */
    @Column("activityType")
    private String activityType;

    /**
     * 地点名称
     */
    @Column("locationName")
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
    @Column("estimatedCostPerPerson")
    private BigDecimal estimatedCostPerPerson;

    /**
     * 逻辑删除：0-未删除，1-已删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

}
