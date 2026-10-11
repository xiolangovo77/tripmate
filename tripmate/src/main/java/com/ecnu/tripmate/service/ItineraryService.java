package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.entity.User;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.Itinerary;

/**
 * 行程表 服务层。
 *
 * @author Wolfcub
 */
public interface ItineraryService extends IService<Itinerary> {

    /**
     * 添加行程
     * @param tripId 旅行id
     * @param initItineraryPrompt 行程初始化提示词
     * @param loginUser 登录用户信息
     * @return
     */
    Long add(Long tripId, String initItineraryPrompt, User loginUser);
}
