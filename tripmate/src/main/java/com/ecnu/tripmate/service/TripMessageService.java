package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.entity.User;
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
}
