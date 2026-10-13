package com.ecnu.tripmate.ai.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class MessageResponseResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 回复消息内容
     */
    private String content;

    /**
     * 建议状态：无修改建议、待处理
     */
    private String changeStatus;
}
