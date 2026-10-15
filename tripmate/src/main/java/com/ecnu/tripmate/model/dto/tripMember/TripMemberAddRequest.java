package com.ecnu.tripmate.model.dto.tripMember;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class TripMemberAddRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 旅行ID
     */
    private Long tripId;

    /**
     * 本次旅行的个人预算上限
     */
    private BigDecimal budgetLimit;

    /**
     * 区分大小写的唯一随机邀请码
     */
    private String inviteCode;
}

