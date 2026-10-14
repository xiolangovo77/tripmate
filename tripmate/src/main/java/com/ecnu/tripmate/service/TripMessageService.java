package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageVO;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.TripMessage;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 旅行消息表 服务层。
 *
 * @author Wolfcub
 */
public interface TripMessageService extends IService<TripMessage> {

    /**
     * 添加用户消息
     * @param tripId 旅行id
     * @param content 消息内容
     * @param loginUser 登录用户信息
     * @param replyToId 回复的消息id
     * @return
     */
    long add(Long tripId, String content, Long replyToId, User loginUser);

    /**
     * 根据id获取消息内容
     * @param id 消息id
     * @return
     */
    String get(Long id);

    /**
     * 获取消息记录
     * @param tripId 旅行id
     * @param pageSize 页面大小
     * @param lastCreateTime 最后一条记录的创建时间
     * @param loginUser 登陆用户信息
     * @return
     */
    List<TripMessageVO> page(Long tripId, int pageSize, LocalDateTime lastCreateTime, User loginUser);

    /**
     * 添加ai消息
     * @param tripId 旅行id
     * @param replyToId 用户消息id
     * @param loginUser 登录用户信息
     * @return
     */
    String addAi(Long tripId, Long replyToId, User loginUser);

    /**
     * 根据建议状态获取的ai消息列表
     * @param tripId 旅行id
     * @param changeStatus 建议状态
     * @param loginUser 登陆用户信息
     * @return
     */
    List<TripMessageAiVO> getAiByChangeStatus(Long tripId, String changeStatus, User loginUser);

    /**
     * 批量修改ai消息的建议状态
     * @param tripId 旅行id
     * @param ids 消息id列表
     * @param changeStatus 建议状态
     * @param loginUser 登录用户信息
     * @return
     */
    int updateChangeStatusByIds(Long tripId, List<Long> ids, String changeStatus, User loginUser);
}
