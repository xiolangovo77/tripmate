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
 * 旅行消息表 实体类。
 *
 * @author Wolfcub
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("trip_message")
public class TripMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 所属旅行ID
     */
    @Column("tripId")
    private Long tripId;

    /**
     * 发送人用户ID，AI消息为空
     */
    @Column("senderId")
    private Long senderId;

    /**
     * 消息角色：用户、AI
     */
    private String role;

    /**
     * 消息内容，用户消息发送成功或AI回复完成后保存
     */
    private String content;

    /**
     * 回复的消息ID，可为空，必须属于同一旅行
     */
    @Column("replyToId")
    private Long replyToId;

    /**
     * 撤回状态：正常、已撤回
     */
    @Column("recallStatus")
    private String recallStatus;

    /**
     * 建议状态：无修改建议、待处理、已应用、已忽略；用户消息和普通AI回复为none
     */
    @Column("changeStatus")
    private String changeStatus;

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
