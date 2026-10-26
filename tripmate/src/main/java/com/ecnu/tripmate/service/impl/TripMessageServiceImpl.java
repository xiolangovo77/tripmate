package com.ecnu.tripmate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ecnu.tripmate.ai.AiItineraryGeneratorService;
import com.ecnu.tripmate.ai.AiItineraryGeneratorServiceFactory;
import com.ecnu.tripmate.cache.TripChatHistoryCache;
import com.ecnu.tripmate.model.dto.ai.TripChatTurn;
import com.ecnu.tripmate.ai.model.MessageResponseResult;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.event.TripSuggestionsChangedEvent;
import com.ecnu.tripmate.event.TripMessagesChangedEvent;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageChangeStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageRecallStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageRoleEnum;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageVO;
import com.ecnu.tripmate.service.*;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.mapper.TripMessageMapper;
import com.ecnu.tripmate.mapper.UserMapper;
import jakarta.annotation.Resource;
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
    private UserMapper userMapper;

    @Resource
    private AiItineraryGeneratorServiceFactory aiItineraryGeneratorServiceFactory;

    @Resource
    private TripChatHistoryCache chatHistoryCache;

    @Resource
    @Lazy
    private ItineraryService itineraryService;

    @Resource
    private ApplicationEventPublisher eventPublisher;

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
    public String get(Long id, User loginUser) {
        if (loginUser == null || loginUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 1、参数校验
        if(id == null || id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id不合法");
        }
        // 2、数据库查询
        TripMessage tripMessage = this.getById(id);
        if(tripMessage == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息不存在");
        }
        // 先校验消息所属旅行的有效成员身份，撤回状态也不能向外部用户泄露。
        checkMessageAccess(tripMessage.getTripId(), loginUser);
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
        // 与单条消息、增量消息使用同一权限规则，已退出成员也不能读历史消息。
        checkMessageAccess(tripId, loginUser);
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
        return toMessageVOs(records);
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
        return toMessageVOs(mapper.selectListByQuery(query));
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
        return toMessageVOs(mapper.selectListByQuery(query));
    }

    private List<TripMessageVO> toMessageVOs(List<TripMessage> records) {
        List<TripMessageVO> messages = BeanUtil.copyToList(records, TripMessageVO.class);
        List<Long> senderIds = records.stream()
                .filter(message -> TripMessageRoleEnum.USER.getValue().equals(message.getRole()))
                .map(TripMessage::getSenderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (senderIds.isEmpty()) {
            return messages;
        }
        // 每批消息只查询一次头像；退出旅行的发送者仍可展示个人头像。
        Map<Long, User> senders = userMapper.selectListByQuery(QueryWrapper.create()
                        .select("id", "nickname", "avatarUrl").in("id", senderIds))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        for (TripMessageVO message : messages) {
            if (!TripMessageRoleEnum.USER.getValue().equals(message.getRole())) {
                continue;
            }
            User sender = senders.get(message.getSenderId());
            if (sender != null) {
                message.setSenderNickname(sender.getNickname());
                message.setSenderAvatarUrl(sender.getAvatarUrl());
            }
        }
        return messages;
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
        // 3、判断行程是否存在
        ItineraryVO itineraryVO = itineraryService.getByTripId(tripId, loginUser);
        if(itineraryVO == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程不存在");
        }
        // 4、判断该用户是否是该旅行的成员
        Long userId = loginUser.getId();
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", userId);
        long count = tripMemberService.getMapper().selectCountByQuery(queryWrapper);
        if(count <= 0){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有该旅行的成员才能查询消息");
        }
        // 5、判断用户消息是否存在
        TripMessage tripMessage = this.getById(replyToId);
        if(tripMessage == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息不存在");
        }
        if (!Objects.equals(tripId, tripMessage.getTripId())
                || !TripMessageRoleEnum.USER.getValue().equals(tripMessage.getRole())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "只能回复本旅行的用户消息");
        }
        // 6、消息是否撤回
        String recallStatus = tripMessage.getRecallStatus();
        if(TripMessageRecallStatusEnum.RECALLED.getValue().equals(recallStatus)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息已撤回");
        }
        // 7、登录用户是否为发消息本人
        long senderId = tripMessage.getSenderId();
        if(senderId != userId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 8、获取旅行和旅行成员信息
        StringBuilder prompt = new StringBuilder();
        String tripAndTripMemberInfos = tripService.getInitItineraryPrompt(tripId, loginUser);
        prompt.append(tripAndTripMemberInfos);
        // 9、获取行程和行程明细信息
        String itineraryInfos = itineraryService.getItineraryInfosPrompt(itineraryVO);
        prompt.append(itineraryInfos);
        // 10、获取用户发送的消息
        String nickname = StrUtil.blankToDefault(loginUser.getNickname(), "未知成员");
        String userContent = tripMessage.getContent();
        String userMessage = buildChatPrompt(prompt.toString(), chatHistoryCache.getRecent(tripId),
                userId, nickname, userContent);
        // 11、调用ai
        AiItineraryGeneratorService aiItineraryGeneratorService = aiItineraryGeneratorServiceFactory.getAiItineraryGeneratorService(tripId);
        MessageResponseResult messageResponseResult = aiItineraryGeneratorService.messageResponse(userMessage);
        if(messageResponseResult == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        // 12、ai返回结果校验
        String content = messageResponseResult.getContent();
        String changeStatus = messageResponseResult.getChangeStatus();
        if(StrUtil.hasBlank(content, changeStatus)){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        if (!TripMessageChangeStatusEnum.NONE.getValue().equals(changeStatus)
                && !TripMessageChangeStatusEnum.PENDING.getValue().equals(changeStatus)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        // 13、添加ai消息
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
        // 只保留原始问题和最终回复；背景、系统提示词、行程和工具结果均不进入 Redis。
        chatHistoryCache.appendAfterCommit(tripId, new TripChatTurn(tripAiMessage.getId(),
                userId, nickname, userContent, content, changeStatus));
        eventPublisher.publishEvent(new TripMessagesChangedEvent(tripId));
        if (!TripMessageChangeStatusEnum.NONE.getValue().equals(changeStatus)) {
            eventPublisher.publishEvent(new TripSuggestionsChangedEvent(tripId));
        }
        return content;
    }

    /** 分别封装最新背景、历史问答和当前问题，避免正文标签干扰输入检查。 */
    private String buildChatPrompt(String background, List<TripChatTurn> history,
                                   Long userId, String nickname, String content) {
        var turns = history.stream().map(turn -> JSONUtil.createObj()
                .set("userId", turn.getUserId()).set("nickname", turn.getNickname())
                .set("question", turn.getQuestion())
                .set("reply", JSONUtil.createObj().set("content", turn.getContent())
                        .set("changeStatus", turn.getChangeStatus()))).toList();
        return JSONUtil.createObj()
                .set("background", background)
                .set("history", turns)
                .set("currentQuestion", JSONUtil.createObj().set("userId", userId)
                        .set("nickname", nickname).set("content", content))
                .toString();
    }

    @Override
    public List<TripMessageAiVO> getAiByChangeStatus(Long tripId, String changeStatus, User loginUser) {
        checkMessageAccess(tripId, loginUser);
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
        if(tripMessageAiList == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "ai消息列表查找失败，数据库错误");
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
