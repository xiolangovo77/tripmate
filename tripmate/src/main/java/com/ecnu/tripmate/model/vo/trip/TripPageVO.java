package com.ecnu.tripmate.model.vo.trip;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
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
     * 旅行天数
     */
    private Integer travelDays;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
