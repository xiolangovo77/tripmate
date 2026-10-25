package com.ecnu.tripmate.model.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一轮纯问答；replyId 用于排序及防止提交回调重复写入。禁止加入旅行或行程快照。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TripChatTurn {
    private Long replyId;
    private Long userId;
    private String nickname;
    private String question;
    private String content;
    private String changeStatus;

}
