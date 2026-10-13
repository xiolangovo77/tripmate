package com.ecnu.tripmate.model.dto.tripMessage;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class TripMessageAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 所属旅行ID
     */
    private Long tripId;

    /**
     * 消息内容，用户消息发送成功或AI回复完成后保存
     */
    private String content;

    /**
     * 回复的消息ID，可为空，必须属于同一旅行
     */
    private Long replyToId;

}

