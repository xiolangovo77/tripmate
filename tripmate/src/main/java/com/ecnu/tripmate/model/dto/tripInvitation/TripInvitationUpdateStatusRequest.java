package com.ecnu.tripmate.model.dto.tripInvitation;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TripInvitationUpdateStatusRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 旅行ID
     */
    private long tripId;

    /**
     * 区分大小写的唯一随机邀请码
     */
    private String inviteCode;

    /**
     * 邀请状态：有效、已撤销；使用时还需检查过期时间
     */
    private String status;
}

