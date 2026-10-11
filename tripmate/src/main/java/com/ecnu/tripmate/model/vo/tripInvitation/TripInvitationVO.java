package com.ecnu.tripmate.model.vo.tripInvitation;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class TripInvitationVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 区分大小写的唯一随机邀请码
     */
    @Column("inviteCode")
    private String inviteCode;

    /**
     * 过期时间，NULL表示不过期
     */
    @Column("expiresTime")
    private LocalDateTime expiresTime;
}
