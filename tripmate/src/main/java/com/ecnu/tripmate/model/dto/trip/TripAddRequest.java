package com.ecnu.tripmate.model.dto.trip;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class TripAddRequest extends TripScheduleRequest {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 本次旅行的个人预算上限
     */
    private BigDecimal budgetLimit;
}

