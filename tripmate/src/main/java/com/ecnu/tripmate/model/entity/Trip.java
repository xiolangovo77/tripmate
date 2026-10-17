package com.ecnu.tripmate.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.math.BigInteger;
import java.time.LocalDateTime;

import java.io.Serial;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 旅行表 实体类。
 *
 * @author Wolfcub
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("trip")
public class Trip implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 旅行ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 发起人用户ID
     */
    @Column("ownerId")
    private Long ownerId;

    /**
     * 旅行名称
     */
    private String title;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 出发地
     */
    @Column("departureLocation")
    private String departureLocation;

    /**
     * 出发日期
     */
    @Column("departureDate")
    private LocalDate departureDate;

    /**
     * 出发时段
     */
    @Column("departurePeriod")
    private String departurePeriod;

    /**
     * 返回出发地的日期
     */
    @Column("returnDate")
    private LocalDate returnDate;

    /**
     * 返回时段
     */
    @Column("returnPeriod")
    private String returnPeriod;


    /**
     * 旅行天数
     */
    @Column("travelDays")
    private Integer travelDays;

    /**
     * 旅行状态：筹备中、已确认、已结束、已取消
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
