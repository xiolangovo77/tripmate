package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.dto.trip.TripPageRequest;
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
     * @param destination 目的地
     * @param travelDays 旅行天数
     * @param budgetLimit 发起人预算
     * @param loginuser 登录用户信息
     * @return 旅行id
     */
    Long addTrip(String destination, Integer travelDays, BigDecimal budgetLimit, User loginuser);

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
     * @param title 旅行标题
     * @param destination 目的地
     * @param travelDays 旅行天数
     * @param loginuser 登录用户信息
     * @return
     */
    boolean update(long tripId, String title, String destination, Integer travelDays, User loginuser);

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
     * 获取旅行和人员信息
     * @param tripId 旅行id
     * @return
     */
    TripVO getTripById(long tripId);

    /**
     * 获取行程初始化的提示词
     * @param tripId 旅行id
     * @return
     */
    String getInitItineraryPrompt(Long tripId);

    /**
     * 分页获取旅行信息
     * @param tripPageRequest 分页获取旅行信息请求
     * @param loginUser 登录用户请求
     * @return
     */
    List<TripPageVO> page(TripPageRequest tripPageRequest, User loginUser);
}
