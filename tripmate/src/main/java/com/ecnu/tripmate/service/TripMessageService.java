package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageVO;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.TripMessage;
import dev.langchain4j.memory.ChatMemory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 旅行消息表 服务层。
 *
 * @author Wolfcub
 */
public interface TripMessageService extends IService<TripMessage> {

    /**
     * 初始化空的旅行记忆：旅行及成员、当前行程及明细、最近20对完整的AI问答。
     * 仅供内部调用；已有记忆时不重复加载，历史问答按时间正序写入。
     * @param tripId 旅行id
     * @param chatMemory id与tripId一致的聊天记忆（配置存储后会自动持久化）
     */
    void loadChatMemory(Long tripId, ChatMemory chatMemory);

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
     * @param lastId 最后一条记录的id，与lastCreateTime成对传入
     * @param loginUser 登陆用户信息
     * @return
     */
    List<TripMessageVO> page(Long tripId, int pageSize, LocalDateTime lastCreateTime, Long lastId, User loginUser);

    /**
     * 增量读取联合游标之后的消息，按createTime、id升序返回，每批1至50条。
     * 游标成对传入；无游标时从最早消息开始，无新消息时返回空列表。
     */
    List<TripMessageVO> latest(Long tripId, int pageSize, LocalDateTime lastCreateTime, Long lastId, User loginUser);

    /** 按ID补查同一旅行内的消息，每批最多50条，仅限已加入成员。 */
    List<TripMessageVO> getByIds(Long tripId, List<Long> ids, User loginUser);

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
