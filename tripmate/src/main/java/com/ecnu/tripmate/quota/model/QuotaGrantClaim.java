package com.ecnu.tripmate.quota.model;

import lombok.Data;

@Data
public class QuotaGrantClaim {
    /** 唯一领取单号。 */
    private String claimNo;
    /** 本单所属活动。 */
    private long activityId;
    /** 领取会员的用户 ID。 */
    private long userId;
    /** 预扣时确定的 AI 使用次数。 */
    private long grantAmount;
    /** 领取状态，pending 为待到账，granted 为已到账。 */
    private String status;
}
