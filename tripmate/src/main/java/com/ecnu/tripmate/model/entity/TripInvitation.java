package com.ecnu.tripmate.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.math.BigInteger;
import java.time.LocalDateTime;

import java.io.Serial;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 旅行邀请表 实体类。
 *
 * @author Wolfcub
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("trip_invitation")
public class TripInvitation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 邀请ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 旅行ID
     */
    @Column("tripId")
    private Long tripId;

    /**
     * 邀请人用户ID
     */
    @Column("inviterId")
    private Long inviterId;

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

    /**
     * 邀请状态：有效、已撤销；使用时还需检查过期时间
     */
    private String status;

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
