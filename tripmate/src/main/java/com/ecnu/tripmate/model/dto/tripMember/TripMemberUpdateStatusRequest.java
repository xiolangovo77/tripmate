package com.ecnu.tripmate.model.dto.tripMember;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class TripMemberUpdateStatusRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 旅行ID
     */
    @Column("tripId")
    private Long tripId;

    /**
     * 用户ID
     */
    @Column("userId")
    private Long userId;

    /**
     * 成员状态：已加入、已退出
     */
    private String status;
}

