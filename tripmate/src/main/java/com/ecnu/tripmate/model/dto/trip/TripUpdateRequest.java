package com.ecnu.tripmate.model.dto.trip;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
public class TripUpdateRequest extends TripScheduleRequest {

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

}

