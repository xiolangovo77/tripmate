package com.ecnu.tripmate.mapper;

import com.ecnu.tripmate.quota.model.QuotaGrantClaim;
import com.ecnu.tripmate.quota.model.QuotaGrantMessage;

public interface QuotaGrantMapper {
    /**
     * 按领取单号查询领取快照和状态，供重投时核对是否已到账。
     *
     * @param claimNo 领取单号
     * @return 匹配的领取单，未找到时返回 {@code null}
     */
    QuotaGrantClaim findByNumber(String claimNo);

    /**
     * 用户存在、活动已发布且每份额度匹配时，建立待到账领取单。
     * 若领取单号或活动与用户的联合唯一键冲突，保留已有记录供事务内核对。
     *
     * @param message 包含领取单号、活动、用户和发放次数的消息快照
     */
    void establishClaim(QuotaGrantMessage message);

    /**
     * 按活动和用户查询领取单，并在当前入账事务中锁定该行，供发放前核对单号和状态。
     *
     * @param message 提供活动 ID 和领取用户 ID 的消息快照
     * @return 锁定的领取单，未找到时返回 {@code null}
     */
    QuotaGrantClaim lockByOwner(QuotaGrantMessage message);

    /**
     * 写入一条正数的额度发放流水，领取单号唯一约束防止同一笔发放重复记账。
     *
     * @param message 提供领取单号、领取用户和发放次数的消息快照
     */
    void insertFlow(QuotaGrantMessage message);

    /**
     * 将发放的 AI 使用次数计入用户余额。首次入账时创建账户，已有账户直接原子累加。
     *
     * @param message 提供领取用户和本次发放次数的消息快照
     */
    void creditAccount(QuotaGrantMessage message);

    /**
     * 将指定领取单从待到账更新为已到账，并记录数据库当前时间作为到账时间。
     *
     * @param claimNo 领取单号
     * @return 更新行数，1 表示完成状态变更，0 表示未找到该单号的待到账记录
     */
    int markGranted(String claimNo);
}
