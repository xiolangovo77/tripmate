package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.dto.trip.*;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.trip.TripPageVO;
import com.ecnu.tripmate.model.vo.trip.TripVO;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.Trip;

import java.math.BigDecimal;
import java.util.List;

/**
 * 旅行表 服务层。
 *
 * @author Wolfcub
 */
public interface TripService extends IService<Trip> {

    /**
     * 创建旅行
     * @param request 旅行路线、日期及预算
     * @param loginuser 登录用户信息
     * @return 旅行id
     */
    Long addTrip(TripAddRequest request, User loginuser);

    /**
     * 更新旅行状态
     * @param tripId 旅行id
     * @param status 旅行状态
     * @param loginuser 登录用户
     * @return
     */
    boolean updateTripStatus(long tripId, String status, User loginuser);

    /**
     * 更新旅行信息
     * @param request 旅行标题、路线与日期
     * @param loginuser 登录用户信息
     * @return
     */
    boolean update(TripUpdateRequest request, User loginuser);

    /**
     * 判断旅行是否存在
     * @param tripId 旅行id
     * @return
     */
    Trip isTripExists(long tripId);

    /**
     * 校验用户是否为旅行发起人
     *
     * @param trip 旅行信息
     * @param loginUser 当前登录用户
     */
    void checkTripOwner(Trip trip, User loginUser);

    /**
     * 获取旅行和人员信息，仅允许当前已加入的成员。
     * @param tripId 旅行id
     * @param loginUser 当前登录用户
     * @return
     */
    TripVO getTripById(long tripId, User loginUser);

    /**
     * 获取行程初始化的提示词，包含成员隐私，调用方必须传入当前登录用户。
     * @param tripId 旅行id
     * @param loginUser 当前登录用户
     * @return
     */
    String getInitItineraryPrompt(Long tripId, User loginUser);

    /**
     * 分页获取旅行信息
     * @param tripPageRequest 分页获取旅行信息请求
     * @param loginUser 登录用户请求
     * @return
     */
    List<TripPageVO> page(TripPageRequest tripPageRequest, User loginUser);
}
