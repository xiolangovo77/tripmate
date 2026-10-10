package com.ecnu.tripmate.model.dto.tripMember;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class TripMemberUpdateRequest implements Serializable {

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
     * 本次旅行的个人预算上限
     */
    @Column("budgetLimit")
    private BigDecimal budgetLimit;

    /**
     * 本次旅行的节奏快照
     */
    @Column("travelPace")
    private String travelPace;

    /**
     * 本次旅行的活动偏好快照
     */
    @Column("favoriteActivities")
    private String favoriteActivities;
}

