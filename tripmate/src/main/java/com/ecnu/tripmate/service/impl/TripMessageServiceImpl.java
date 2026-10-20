package com.ecnu.tripmate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ecnu.tripmate.ai.AiItineraryGeneratorService;
import com.ecnu.tripmate.ai.AiItineraryGeneratorServiceFactory;
import com.ecnu.tripmate.ai.model.ItineraryItemResult;
import com.ecnu.tripmate.ai.model.ItineraryResult;
import com.ecnu.tripmate.ai.model.MessageResponseResult;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.event.TripSuggestionsChangedEvent;
import com.ecnu.tripmate.event.TripMessagesChangedEvent;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.TripMember;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.trip.TripMemberRoleEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageChangeStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageRecallStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageRoleEnum;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryItemVO;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageVO;
import com.ecnu.tripmate.service.*;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.mapper.TripMessageMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 旅行消息表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class TripMessageServiceImpl extends ServiceImpl<TripMessageMapper, TripMessage>  implements TripMessageService{

    @Resource
    private TripService tripService;

    @Resource
    private TripMemberService tripMemberService;

    @Resource
    private AiItineraryGeneratorServiceFactory aiItineraryGeneratorServiceFactory;

    @Resource
    @Lazy
    private ItineraryService itineraryService;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    private static final int HISTORY_PAIR_LIMIT = 20;

    @Override
    public void loadChatMemory(Long tripId, ChatMemory chatMemory) {
        if (tripId == null || tripId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if (chatMemory == null || !Objects.equals(tripId, chatMemory.id())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "聊天记忆与旅行不匹配");
        }
        if (!chatMemory.messages().isEmpty()) {
            return;
        }

        StringBuilder context = new StringBuilder("以下为初始化时的旅行背景，当前请求提供的信息优先。\n")
                .append(tripService.getInitItineraryPrompt(tripId));
        Long itineraryId = itineraryService.getIdByTripId(tripId);
        if (itineraryId != null) {
            context.append('\n').append(itineraryService.getItineraryInfosPrompt(itineraryService.get(itineraryId)));
        } else {
            context.append("\n【当前行程信息】\n尚未生成行程。\n");
        }

        // 以AI回复关联用户问题识别@AI问答，不依赖消息正文是否包含“@AI”。
        List<TripMessage> replies = mapper.selectRecentAiReplies(tripId, HISTORY_PAIR_LIMIT);
        List<ChatMessage> history = new ArrayList<>();
        // 背景使用普通消息，避免被各AI方法的@SystemMessage替换。
        history.add(UserMessage.from(context.toString()));
        history.add(AiMessage.from("已收到旅行背景，将结合当前请求中的最新信息回答。"));
        if (!replies.isEmpty()) {
            List<Long> questionIds = replies.stream().map(TripMessage::getReplyToId).distinct().toList();
            QueryWrapper questionQuery = new QueryWrapper()
                    .in("id", questionIds)
                    .eq("tripId", tripId)
                    .eq("role", TripMessageRoleEnum.USER.getValue())
                    .eq("recallStatus", TripMessageRecallStatusEnum.NORMAL.getValue());
            Map<Long, TripMessage> questions = mapper.selectListByQuery(questionQuery).stream()
                    .collect(Collectors.toMap(TripMessage::getId, Function.identity()));
            // 批量读取旅行成员昵称，包含已退出成员，供历史消息使用。
            Map<Long, String> nicknames = tripMemberService.list(new QueryWrapper().eq("tripId", tripId))
                    .stream().collect(Collectors.toMap(TripMember::getUserId,
                            member -> StrUtil.blankToDefault(member.getNickname(), "未知成员"),
                            (first, second) -> first));
            // 查询结果倒序，成对反向遍历，保证写入顺序为旧问题、旧回答、新问题、新回答。
            for (int i = replies.size() - 1; i >= 0; i--) {
                TripMessage reply = replies.get(i);
                TripMessage question = questions.get(reply.getReplyToId());
                if (question == null || StrUtil.isBlank(question.getContent())) {
                    continue;
                }
                history.add(UserMessage.from(String.format("用户昵称：%s\n消息内容：%s",
                        nicknames.getOrDefault(question.getSenderId(), "未知成员"), question.getContent())));
                // 恢复模型原本的JSON输出契约；已应用/忽略是业务状态，不是模型输出状态。
                MessageResponseResult response = new MessageResponseResult();
                response.setContent(reply.getContent());
                response.setChangeStatus(TripMessageChangeStatusEnum.NONE.getValue().equals(reply.getChangeStatus())
                        ? TripMessageChangeStatusEnum.NONE.getValue() : TripMessageChangeStatusEnum.PENDING.getValue());
                history.add(AiMessage.from(JSONUtil.toJsonStr(response)));
            }
        }
        // 完成所有数据库读取后再写记忆，避免查询失败留下不完整的初始化内容。
        chatMemory.add(history);
    }

    @Override
    @Transactional
    public long add(Long tripId, String content, Long replyToId, User loginUser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(StrUtil.hasBlank(content)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息不能为空");
        }
        if(replyToId != null && replyToId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "回复id不合法");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、判断该用户是否是该旅行的成员
        Long userId = loginUser.getId();
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", loginUser.getId());
        long count = tripMemberService.getMapper().selectCountByQuery(queryWrapper);
        if(count <= 0){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有该旅行的成员才能发送消息");
        }
        // 4、判断回复的消息是否存在
        if(replyToId != null){
            TripMessage replyMessage = this.getById(replyToId);
            if(replyMessage == null){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "回复的消息不存在");
            }
        }
        // 5、设置默认值
        String role = TripMessageRoleEnum.USER.getValue();
        String changeStatus = TripMessageChangeStatusEnum.NONE.getValue();
        String recallStatus = TripMessageRecallStatusEnum.NORMAL.getValue();
        // 6、操作数据库
        TripMessage tripMessage = TripMessage.builder()
                .tripId(tripId)
                .senderId(userId)
                .role(role)
                .content(content)
                .replyToId(replyToId)
                .recallStatus(recallStatus)
                .changeStatus(changeStatus)
                .build();
        boolean saveResult = this.save(tripMessage);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "消息添加失败，数据库错误");
        }
        eventPublisher.publishEvent(new TripMessagesChangedEvent(tripId));
        return tripMessage.getId();
    }

    @Override
    public String get(Long id) {
        // 1、参数校验
        if(id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id不合法");
        }
        // 2、数据库查询
        TripMessage tripMessage = this.getById(id);
        if(tripMessage == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息不存在");
        }
        // 3、判断消息是否撤回
        String recallStatus = tripMessage.getRecallStatus();
        // 如果撤回
        if(TripMessageRecallStatusEnum.RECALLED.getValue().equals(recallStatus)){
            return "消息已撤回";
        }
        return tripMessage.getContent();
    }

    @Override
    public List<TripMessageVO> page(Long tripId, int pageSize, LocalDateTime lastCreateTime, Long lastId, User loginUser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(!(pageSize >= 0 && pageSize <= 50)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "页面大小不合法");
        }
        if ((lastCreateTime == null) != (lastId == null)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "联合游标不合法");
        }
        if (lastId != null && lastId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息id游标不合法");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、判断该用户是否是该旅行的成员
        Long userId = loginUser.getId();
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", loginUser.getId());
        long count = tripMemberService.getMapper().selectCountByQuery(queryWrapper);
        if(count <= 0){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有该旅行的成员才能查询消息");
        }
        // 4、封装分页sql请求
        QueryWrapper queryWrapper1 = new QueryWrapper();
        queryWrapper1.eq("tripId", tripId);
        // 游标查询逻辑 - 用（lastCreateTime，lastId）作为游标
        if (lastCreateTime != null) {
            queryWrapper1.and(
                    "(createTime < ? OR (createTime = ? AND id < ?))",
                    lastCreateTime, lastCreateTime, lastId
            );
        }
        // 默认按照创建时间降序
        queryWrapper1.orderBy("createTime", false);
        queryWrapper1.orderBy("id", false);
        // 5、查询数据库
        Page<TripMessage> page = this.page(Page.of(1, pageSize), queryWrapper1);
        // 6、返回VO
        List<TripMessage> records = page.getRecords();
        List<TripMessageVO> tripMessageVOList = BeanUtil.copyToList(records, TripMessageVO.class);
        return tripMessageVOList;
    }

    @Override
    public List<TripMessageVO> latest(Long tripId, int pageSize, LocalDateTime lastCreateTime,
                                      Long lastId, User loginUser) {
        if (pageSize < 1 || pageSize > 50) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "页面大小不合法");
        }
        if ((lastCreateTime == null) != (lastId == null) || (lastId != null && lastId <= 0)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "联合游标不合法");
        }
        checkMessageAccess(tripId, loginUser);
        QueryWrapper query = QueryWrapper.create().eq("tripId", tripId);
        if (lastCreateTime != null) {
            query.and("(createTime > ? OR (createTime = ? AND id > ?))",
                    lastCreateTime, lastCreateTime, lastId);
        }
        // 必须从游标后的第一条开始升序读取，不能倒序截取最新一批而跳过中间消息。
        query.orderBy("createTime", true).orderBy("id", true).limit(pageSize);
        return BeanUtil.copyToList(mapper.selectListByQuery(query), TripMessageVO.class);
    }

    @Override
    public List<TripMessageVO> getByIds(Long tripId, List<Long> ids, User loginUser) {
        if (ids == null || ids.isEmpty() || ids.size() > 50
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息ids不合法，每次最多50条");
        }
        checkMessageAccess(tripId, loginUser);
        QueryWrapper query = QueryWrapper.create().eq("tripId", tripId).in("id", ids)
                .orderBy("createTime", true).orderBy("id", true);
        return BeanUtil.copyToList(mapper.selectListByQuery(query), TripMessageVO.class);
    }

    private void checkMessageAccess(Long tripId, User loginUser) {
        if (tripId == null || tripId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if (loginUser == null || loginUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        tripMemberService.checkMemberAccess(tripId, loginUser.getId());
    }

    @Override
    @Transactional
    public String addAi(Long tripId, Long replyToId, User loginUser) {
        // 1、校验参数
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(replyToId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息id不合法");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、判断该用户是否是该旅行的成员
        Long userId = loginUser.getId();
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", userId);
        long count = tripMemberService.getMapper().selectCountByQuery(queryWrapper);
        if(count <= 0){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有该旅行的成员才能查询消息");
        }
        // 4、判断用户消息是否存在
        TripMessage tripMessage = this.getById(replyToId);
        if(tripMessage == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息不存在");
        }
        // 5、消息是否撤回
        String recallStatus = tripMessage.getRecallStatus();
        if(TripMessageRecallStatusEnum.RECALLED.getValue().equals(recallStatus)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息已撤回");
        }
        // 6、登录用户是否为发消息本人
        long senderId = tripMessage.getSenderId();
        if(senderId != userId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 7、获取旅行和旅行成员信息
        StringBuilder prompt = new StringBuilder();
        String tripAndTripMemberInfos = tripService.getInitItineraryPrompt(tripId);
        prompt.append(tripAndTripMemberInfos);
        // 8、获取行程和行程明细信息
        Long itineraryId = itineraryService.getIdByTripId(tripId);
        ItineraryVO itineraryVO = itineraryService.get(itineraryId);
        String itineraryInfos = itineraryService.getItineraryInfosPrompt(itineraryVO);
        prompt.append(itineraryInfos);
        // 9、获取用户发送的消息
        String nickname = loginUser.getNickname();
        String userContent = tripMessage.getContent();
        String userMessage = String.format("以下是旅行群聊中用户发送的消息，请结合用户的需求进行回复。\n用户昵称：%s\n消息内容：%s", nickname, userContent);
        prompt.append(userMessage);
        // 10、调用ai
        AiItineraryGeneratorService aiItineraryGeneratorService = aiItineraryGeneratorServiceFactory.getAiItineraryGeneratorService(tripId);
        MessageResponseResult messageResponseResult = aiItineraryGeneratorService.messageResponse(prompt.toString());
        if(messageResponseResult == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        // 11、ai返回结果校验
        String content = messageResponseResult.getContent();
        String changeStatus = messageResponseResult.getChangeStatus();
        if(StrUtil.hasBlank(content, changeStatus)){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        if(TripMessageChangeStatusEnum.getEnumByValue(changeStatus) == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        // 12、添加ai消息
        String role = TripMessageRoleEnum.ASSISTANT.getValue();
        String recallStatusAi = TripMessageRecallStatusEnum.NORMAL.getValue();
        TripMessage tripAiMessage = TripMessage.builder()
                .tripId(tripId)
                .role(role)
                .content(content)
                .replyToId(replyToId)
                .recallStatus(recallStatusAi)
                .changeStatus(changeStatus)
                .build();
        boolean saveResult = this.save(tripAiMessage);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存ai消息失败，数据库错误");
        }
        eventPublisher.publishEvent(new TripMessagesChangedEvent(tripId));
        if (!TripMessageChangeStatusEnum.NONE.getValue().equals(changeStatus)) {
            eventPublisher.publishEvent(new TripSuggestionsChangedEvent(tripId));
        }
        return content;
    }

    @Override
    public List<TripMessageAiVO> getAiByChangeStatus(Long tripId, String changeStatus, User loginUser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(StrUtil.hasBlank(changeStatus) || TripMessageChangeStatusEnum.getEnumByValue(changeStatus) == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "建议状态不合法");
        }
        // 2、判断旅行是否存在
        Trip trip = tripService.isTripExists(tripId);
        // 3、判断该用户是否是该旅行的发起人
        long userId = loginUser.getId();
        long ownerId = trip.getOwnerId();
        if(userId != ownerId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有发起人才可以查询");
        }
        // 4、查询数据库
        String role = TripMessageRoleEnum.ASSISTANT.getValue();
        QueryWrapper queryWrapper1 = new QueryWrapper();
        queryWrapper1.eq("tripId", tripId)
                .eq("changeStatus", changeStatus)
                .eq("role", role)
                .orderBy("createTime", true);
        List<TripMessage> tripMessageAiList = this.mapper.selectListByQuery(queryWrapper1);
        if(tripMessageAiList == null || tripMessageAiList.isEmpty()){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "ai消息列表为空");
        }
        // 5、返回VO
        List<TripMessageAiVO> TripMessageAiVOList = BeanUtil.copyToList(tripMessageAiList, TripMessageAiVO.class);
        return TripMessageAiVOList;
    }

    @Override
    @Transactional
    public int updateChangeStatusByIds(Long tripId, List<Long> ids, String changeStatus, User loginUser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(ids == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息ids不能为空");
        }
        for(Long id : ids){
            if(id == null || id <= 0){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息id不合法");
            }
        }
        if(StrUtil.hasBlank(changeStatus) || TripMessageChangeStatusEnum.getEnumByValue(changeStatus) == null
        || (changeStatus.equals(TripMessageChangeStatusEnum.APPLIED.getValue())
                && changeStatus.equals(TripMessageChangeStatusEnum.IGNORED.getValue()))){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "建议状态不合法");
        }
        // 2、判断旅行是否存在
        Trip trip = tripService.isTripExists(tripId);
        // 3、判断该用户是否是该旅行的发起人
        long userId = loginUser.getId();
        long ownerId = trip.getOwnerId();
        if(userId != ownerId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有发起人才可以查询");
        }
        // 4、判断消息列表是否合法
        // 去重
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(ids));
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.in("id", distinctIds)
                .eq("tripId", tripId);
        List<TripMessage> tripMessageList = this.mapper.selectListByQuery(queryWrapper);
        if(tripMessageList.size() != distinctIds.size()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "部分消息不存在或不属于该旅行");
        }
        String assistant = TripMessageRoleEnum.ASSISTANT.getValue();
        String normal = TripMessageRecallStatusEnum.NORMAL.getValue();
        for(TripMessage tripMessage : tripMessageList){
            if(!assistant.equals(tripMessage.getRole())){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "只能修改AI消息的建议状态");
            }
            if(!normal.equals(tripMessage.getRecallStatus())){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "已撤回的消息不能修改建议状态");
            }
            if(!TripMessageChangeStatusEnum.PENDING.getValue().equals(tripMessage.getChangeStatus())){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "只能修改待处理的建议");
            }
        }
        // 5、操作数据库
        QueryWrapper updateQueryWrapper = new QueryWrapper();
        updateQueryWrapper.in("id", distinctIds)
                .eq("tripId", tripId)
                .eq("role", assistant)
                .eq("recallStatus", normal)
                .eq("changeStatus", TripMessageChangeStatusEnum.PENDING.getValue());

        TripMessage updateMessage = TripMessage.builder()
                .changeStatus(changeStatus)
                .build();
        int updateCount = this.mapper.updateByQuery(updateMessage, updateQueryWrapper);
        if(updateCount != distinctIds.size()){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "修改建议状态失败");
        }
        // 采纳建议时加入行程更新的外层事务，AI生成失败回滚时不会推送。
        eventPublisher.publishEvent(new TripSuggestionsChangedEvent(tripId));
        return updateCount;
    }
}
