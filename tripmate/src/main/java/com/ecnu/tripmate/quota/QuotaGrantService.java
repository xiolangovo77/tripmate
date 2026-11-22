package com.ecnu.tripmate.quota;

import com.ecnu.tripmate.mapper.QuotaGrantMapper;
import com.ecnu.tripmate.quota.model.QuotaGrantClaim;
import com.ecnu.tripmate.quota.model.QuotaGrantMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuotaGrantService {
    /** 在同一事务中读写领取单、发放流水及额度账户。 */
    private final QuotaGrantMapper quotaGrantMapper;

    @Transactional
    public void grant(QuotaGrantMessage message) {
        //1、已提交的到账记录是终态，重投只需核对完整快照。
        QuotaGrantClaim existing = quotaGrantMapper.findByNumber(message.getClaimNo());
        if (existing != null) {
            verifySnapshot(existing, message);
            if ("granted".equals(existing.getStatus())) return;
        }

        //2、按活动发布额度建立领取单，两个唯一键负责串行化并发首次领取。
        quotaGrantMapper.establishClaim(message);
        QuotaGrantClaim locked = quotaGrantMapper.lockByOwner(message);
        if (locked == null) {
            throw new IllegalArgumentException("单号冲突或活动、用户、发放额度无效：" + message.getClaimNo());
        }
        verifySnapshot(locked, message);
        if ("granted".equals(locked.getStatus())) return;

        //3、流水、余额和领取状态共同提交，任何写入失败都会回滚。
        quotaGrantMapper.insertFlow(message);
        quotaGrantMapper.creditAccount(message);
        if (quotaGrantMapper.markGranted(message.getClaimNo()) != 1) {
            throw new IllegalStateException("领取状态更新失败：" + message.getClaimNo());
        }
    }

    private void verifySnapshot(QuotaGrantClaim claim, QuotaGrantMessage message) {
        if (!claim.getClaimNo().equals(message.getClaimNo()) || claim.getActivityId() != message.getActivityId()
                || claim.getUserId() != message.getUserId() || claim.getGrantAmount() != message.getGrantAmount()) {
            throw new IllegalArgumentException("领取快照冲突，已有单号=" + claim.getClaimNo()
                    + "，收到单号=" + message.getClaimNo());
        }
    }
}
