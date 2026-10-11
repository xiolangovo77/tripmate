package com.ecnu.tripmate.ai.model;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ItineraryResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 行程标题
     */
    private String title;

    /**
     * 行程概述及推荐理由
     */
    private String summary;

    /**
     * 每人预计费用，NULL表示尚未估算
     */
    private BigDecimal estimatedCostPerPerson;

    /**
     * 行程明细表
     */
    List<ItineraryItemResult> itineraryItemResultList;
}
