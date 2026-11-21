package com.ecnu.tripmate.quota.model;

import lombok.Data;

/**
 * 库存核对使用的会员和领取单号。
 */
@Data
public class ActivityClaim {
    /**
     * 领取会员的用户ID
     */
    private long userId;

    /**
     * 用于核对 Redis 预扣记录的领取单号
     */
    private String claimNo;
}
