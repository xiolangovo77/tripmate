package com.ecnu.tripmate.quota.model;

import lombok.Value;

@Value
public class QuotaGrantMessage {
    /** 领取单号，同一预扣的所有重投使用相同值。 */
    String claimNo;
    /** 所属活动 ID。 */
    long activityId;
    /** 领取用户 ID。 */
    long userId;
    /** 本单发放的 AI 助手使用次数。 */
    long grantAmount;
}
