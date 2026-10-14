package com.ecnu.tripmate.model.vo.tripMessage;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class TripMessageAiVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 消息ID
     */
    private Long id;

    /**
     * 消息内容，用户消息发送成功或AI回复完成后保存
     */
    private String content;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
