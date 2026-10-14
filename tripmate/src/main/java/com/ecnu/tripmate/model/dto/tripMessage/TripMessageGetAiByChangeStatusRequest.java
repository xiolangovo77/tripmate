package com.ecnu.tripmate.model.dto.tripMessage;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TripMessageGetAiByChangeStatusRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 所属旅行ID
     */
    private Long tripId;

    /**
     * 建议状态：无修改建议、待处理、已应用、已忽略；用户消息和普通AI回复为none
     */
    private String changeStatus;
}

