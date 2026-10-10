package com.ecnu.tripmate.model.dto.trip;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TripUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 旅行ID
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
}

