package com.ecnu.tripmate.model.dto.trip;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class TripAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 旅行天数
     */
    @Column("travelDays")
    private Integer travelDays;

    /**
     * 本次旅行的个人预算上限
     */
    @Column("budgetLimit")
    private BigDecimal budgetLimit;
}

