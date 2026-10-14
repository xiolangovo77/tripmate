package com.ecnu.tripmate.model.dto.Itinerary;

import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class ItineraryUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 行程ID
     */
    private Long id;

    /**
     * 消息id列表
     */
    private List<TripMessageAiVO> messageList;
}

