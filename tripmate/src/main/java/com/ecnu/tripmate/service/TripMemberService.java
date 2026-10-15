package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.User;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.TripMember;

import java.math.BigDecimal;
import java.util.List;

/**
 * 旅行成员表 服务层。
 *
 * @author Wolfcub
 */
public interface TripMemberService extends IService<TripMember> {

    /**
     * 加入成员信息
     * @param tripId 旅行id
     * @param role 成员角色
     * @param inviteCode 邀请码
     * @param logiguser 成员信息
     * @param budgetLimit 成员旅行预算
     * @return
     */
    Long add(long tripId, String role, String inviteCode, User logiguser, BigDecimal budgetLimit);

    /**
     * 修改旅行成员状态
     * @param tripId 旅行id
     * @param userId 用户id
     * @param status 旅行成员状态
     * @param loginuser 旅行成员信息
     * @return
     */
    boolean updateTripMemberStatus(long tripId, long userId, String status, User loginuser);

    /**
     * 更新旅行成员信息
     * @param tripId 旅行id
     * @param userId 用户id
     * @param budgetLimit 预算
     * @param travelPace 旅行节奏
     * @param favoriteActivities 旅行偏好
     * @param loginuser
     * @return
     */
    boolean update(long tripId, long userId, BigDecimal budgetLimit, String travelPace, String favoriteActivities, User loginuser);

    /**
     * 根据用户id获取已加入的旅游id列表
     * @param userId
     * @return
     */
    List<Long> getTripIdsByUserId(long userId);
}
