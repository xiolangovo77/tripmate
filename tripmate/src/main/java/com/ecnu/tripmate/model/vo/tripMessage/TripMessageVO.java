package com.ecnu.tripmate.model.vo.tripMessage;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class TripMessageVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 消息ID
     */
    private Long id;

    /**
     * 发送人用户ID，AI消息为空
     */
    private Long senderId;

    /**
     * 消息角色：用户、AI
     */
    private String role;

    /**
     * 消息内容，用户消息发送成功或AI回复完成后保存
     */
    private String content;

    /**
     * 回复的消息ID，可为空，必须属于同一旅行
     */
    private Long replyToId;

    /**
     * 撤回状态：正常、已撤回
     */
    private String recallStatus;

    /**
     * 建议状态：无修改建议、待处理、已应用、已忽略；用户消息和普通AI回复为none
     */
    private String changeStatus;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
