package com.ecnu.tripmate.model.dto.user;

import com.mybatisflex.annotation.Column;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class UserUpdateRequest implements Serializable {
    @Serial
    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 账号
     */
    @Column("userAccount")
    private String userAccount;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像地址
     */
    @Column("avatarUrl")
    private String avatarUrl;

    /**
     * 旅行节奏：悠闲、适中、充实
     */
    @Column("travelPace")
    private String travelPace;

    /**
     * 活动偏好数组：美食、自然、拍照、文化、购物
     */
    @Column("favoriteActivities")
    private String favoriteActivities;
}
