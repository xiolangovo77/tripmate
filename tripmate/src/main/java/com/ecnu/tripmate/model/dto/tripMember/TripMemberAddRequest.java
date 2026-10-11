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
     * 成员角色：发起人、普通成员
     */
    private String role;
}

