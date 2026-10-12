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
 * 行程表 实体类。
 *
 * @author Wolfcub
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("itinerary")
public class Itinerary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 行程ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 旅行ID，一次旅行对应一份当前行程
     */
    @Column("tripId")
    private Long tripId;

    /**
     * 行程标题
     */
    private String title;

    /**
     * 行程概述及推荐理由
     */
    private String summary;

    /**
     * 每人预计费用，NULL表示尚未估算
     */
    @Column("estimatedCostPerPerson")
    private BigDecimal estimatedCostPerPerson;

    /**
     * 行程状态：草案、已确认
     */
    private String status;

    /**
     * 确认人用户ID
     */
    @Column("confirmedBy")
    private Long confirmedBy;

    /**
     * 确认时间
     */
    @Column("confirmedTime")
    private LocalDateTime confirmedTime;

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
