package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.tripInvitation.TripInvitationVO;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.TripInvitation;

/**
 * 旅行邀请表 服务层。
 *
 * @author Wolfcub
 */
public interface TripInvitationService extends IService<TripInvitation> {

    /**
     * 初始化邀请码
     * @param tripId 旅行id
     * @param inviteCode 邀请码
     * @param loginuser 登录用户信息
     * @return
     */
    long add(long tripId, String inviteCode, User loginuser);

    /**
     * 修改邀请码状态
     * @param tripId 旅行id
     * @param inviteCode 邀请码
     * @param loginuser 登录用户信息
     * @return
     */
    boolean updateStatus(long tripId, String inviteCode, String status, User loginuser);

    /**
     * 获取旅行的邀请码
     * @param tripId 旅行id
     * @param loginuser 登录用户信息
     * @return
     */
    TripInvitationVO get(long tripId, User loginuser);

    /**
     * 检验邀请码
     * @param tripId 旅行id
     * @param InviteCode 邀请码
     * @return
     */
    boolean checkInviteCode(long tripId, String InviteCode);
}
