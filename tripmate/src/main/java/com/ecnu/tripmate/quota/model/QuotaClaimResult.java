package com.ecnu.tripmate.quota.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaClaimResult {
    /**
     * 活动 ID。
     */
    private long activityId;
    /**
     * 同一活动、同一用户的唯一领取单号。
     */
    private String claimNo;
    /**
     * PROCESSING 表示已受理，GRANTED 表示 MySQL 已记录到账。
     */
    private String status;
}
