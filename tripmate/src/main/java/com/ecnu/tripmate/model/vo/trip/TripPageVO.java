package com.ecnu.tripmate.model.vo.trip;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TripPageVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 旅行id
     */
    private Long id;

    /**
     * 旅行名称
     */
    private String title;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 出发地
     */
    private String departureLocation;

    /**
     * 出发日期
     */
    private LocalDate departureDate;

    /**
     * 出发时段
     */
    private String departurePeriod;

    /**
     * 返回出发地的日期
     */
    private LocalDate returnDate;

    /**
     * 返回时段
     */
    private String returnPeriod;

    /**
     * 旅行状态：planning-筹备中、confirmed-已确认、finished-已结束、cancelled-已取消
     */
    private String status;

    /**
     * 旅行天数
     */
    private Integer travelDays;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
