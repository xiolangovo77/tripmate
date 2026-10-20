package com.ecnu.tripmate.model.vo.itinerary;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ItineraryVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 当前行程ID，采纳建议替换行程后会变化
     */
    private Long id;

    /**
     * 旅行ID，一次旅行对应一份当前行程
     */
    private Long tripId;

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
     * 行程状态：草案、已确认
     */
    private String status;

    /**
     * 行程明细
     */
    private List<ItineraryItemVO> itineraryItemVOList;
}
