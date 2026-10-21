package com.ecnu.tripmate.model.vo.trip;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class TripMemberVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 成员角色：发起人、普通成员
     */
    private String role;

    /**
     * 昵称
     */
    private String nickname;

    /** 当前个人头像，从 user 表读取，不保存到 trip_member 表。 */
    private String avatarUrl;

    /**
     * 本次旅行的个人预算上限
     */
    private BigDecimal budgetLimit;

    /**
     * 本次旅行的节奏快照
     */
    private String travelPace;

    /**
     * 本次旅行的活动偏好快照
     */
    private String favoriteActivities;
}
