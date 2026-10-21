package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.User;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.TripMember;
import com.ecnu.tripmate.model.vo.trip.TripMemberVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 旅行成员表 服务层。
 *
 * @author Wolfcub
 */
public interface TripMemberService extends IService<TripMember> {

    /** 内部转换成员返回对象，批量补充用户当前头像；调用方负责查询权限。 */
    List<TripMemberVO> toMemberVOs(List<TripMember> members);

    /**
     * 获取旅行当前已加入的成员列表，并校验查询用户的成员权限。
     *
     * @param tripId 旅行ID
     * @param userId 当前登录用户ID
     * @return 按加入时间和成员记录ID升序排列的成员列表
     */
    List<TripMemberVO> getActiveMembers(long tripId, long userId);

    /**
     * 校验旅行是否存在，以及用户是否仍有成员访问权限。
     *
     * @param tripId 旅行ID
     * @param userId 待校验的用户ID
     */
    void checkMemberAccess(long tripId, long userId);

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
