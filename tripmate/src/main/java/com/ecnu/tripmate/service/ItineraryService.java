package com.ecnu.tripmate.service;

import com.ecnu.tripmate.ai.model.ItineraryResult;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.Itinerary;

import java.util.List;

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

    /**
     * 确认行程
     * @param id 行程id
     * @param loginuser 登录用户信息
     * @return
     */
    int confirmed(Long id, User loginuser);

    /**
     * 获取行程和行程明细
     * @param id 行程id
     * @return
     */
    ItineraryVO get(Long id);

    /**
     * 更新行程和行程明细
     * @param id 行程id
     * @param messageList ai消息列表
     * @param loginuser 登陆用户信息
     * @return
     */
    long update(Long id, List<TripMessageAiVO> messageList, User loginuser);

    /**
     * 获取行程更新的提示词
     * @param itineraryVO 行程和行程明细的信息
     * @return
     */
    String getItineraryInfosPrompt(ItineraryVO itineraryVO);

    /**
     * 删除行程和行程明细
     * @param id 行程id
     * @return
     */
    boolean delete(Long id);

    /**
     * 根据旅行id获取行程id
     * @param tripId 旅行id
     * @return
     */
    Long getIdByTripId(Long tripId);
}
