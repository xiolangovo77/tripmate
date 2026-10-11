package com.ecnu.tripmate.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;

import java.io.Serial;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 旅行成员表 实体类。
 *
 * @author Wolfcub
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("trip_member")
public class TripMember implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 成员记录ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

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
     * 昵称
     */
    private String nickname;

    /**
     * 成员角色：发起人、普通成员
     */
    private String role;

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

    /**
     * 成员状态：已加入、已退出
     */
    private String status;

    /**
     * 最近一次加入时间
     */
    @Column("joinedTime")
    private LocalDateTime joinedTime;

    /**
     * 逻辑删除：0-未删除，1-已删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

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
