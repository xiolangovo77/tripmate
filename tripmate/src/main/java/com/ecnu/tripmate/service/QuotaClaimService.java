package com.ecnu.tripmate.service;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.mapper.QuotaActivityMapper;
import com.ecnu.tripmate.cache.quota.ActivityCache;
import com.ecnu.tripmate.cache.quota.ActivityRedis;
import com.ecnu.tripmate.quota.model.QuotaClaimResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuotaClaimService {
    /** 查询领取资格和当前用户的持久化领取结果。 */
    private final QuotaActivityMapper quotaActivityMapper;
    /** 读取已提交发布的活动配置。 */
    private final ActivityCache activityCache;
    /** 原子预扣库存并保存可重投的消息。 */
    private final ActivityRedis activityRedis;

    public QuotaClaimResult claim(long activityId, long userId) {
        //1、只受理已发布的活动，按登录用户实时读取会员到期时间。
        var config = activityCache.get(activityId);
        if (!config.published()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Long expiry = quotaActivityMapper.memberExpiry(userId);
        //2、Lua 返回首次预扣单号或原单号，生成的候选单号最长 52 字符。
        String candidate = activityId + "-" + UUID.randomUUID().toString().replace("-", "");
        String result = activityRedis.reserve(config, userId, candidate, expiry == null ? 0 : expiry);
        return new QuotaClaimResult(activityId, result, "PROCESSING");
    }

    public QuotaClaimResult find(long activityId, long userId) {
        //1、按活动和登录用户查询数据库，优先返回持久化状态。
        var row = quotaActivityMapper.findClaim(activityId, userId);
        if (row == null) {
            //2、未落库时，使用同一用户的预扣标记返回处理中。
            String number = activityRedis.reservation(activityId, userId);
            if (number != null) row = new QuotaClaimResult(activityId, number, "PROCESSING");
        }
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "领取单不存在");
        }
        return row;
    }

}
