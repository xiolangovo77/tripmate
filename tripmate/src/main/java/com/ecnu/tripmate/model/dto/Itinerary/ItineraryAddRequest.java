package com.ecnu.tripmate.model.dto.Itinerary;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class ItineraryAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 旅行ID，一次旅行对应一份当前行程
     */
    private Long tripId;

    /**
     * 行程初始化提示词
     */
    private String initItineraryPrompt;
}

