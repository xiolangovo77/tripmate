package com.ecnu.tripmate.model.dto.tripMessage;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TripMessageAddAiRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 所属旅行ID
     */
    private Long tripId;

    /**
     * 回复的消息ID，可为空，必须属于同一旅行
     */
    private Long replyToId;

}

