package com.ecnu.tripmate.model.vo.user;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class LoginUserVO implements Serializable {
    /**
     * 用户ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

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

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;
}
