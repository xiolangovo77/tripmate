package com.ecnu.tripmate.service.impl;

import cn.hutool.core.util.StrUtil;
import com.ecnu.tripmate.ai.AiItineraryGeneratorService;
import com.ecnu.tripmate.ai.AiItineraryGeneratorServiceFactory;
import com.ecnu.tripmate.ai.model.ItineraryItemResult;
import com.ecnu.tripmate.ai.model.ItineraryResult;
import com.ecnu.tripmate.cache.ItineraryCache;
import com.ecnu.tripmate.event.TripItineraryChangedEvent;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.ItineraryItem;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.vo.trip.TripMemberVO;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.ItineraryStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageChangeStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageRecallStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageRoleEnum;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryItemVO;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.ecnu.tripmate.service.ItineraryItemService;
import com.ecnu.tripmate.service.TripMemberService;
import com.ecnu.tripmate.service.TripService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.Itinerary;
import com.ecnu.tripmate.mapper.ItineraryMapper;
import com.ecnu.tripmate.service.ItineraryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 行程表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class ItineraryServiceImpl extends ServiceImpl<ItineraryMapper, Itinerary>  implements ItineraryService{

    @Resource
    private TripService tripService;

    @Resource
    private AiItineraryGeneratorServiceFactory aiItineraryGeneratorServiceFactory;

    @Resource
    private ItineraryItemService itineraryItemService;

    @Resource
    private TripMessageServiceImpl tripMessageService;

    @Resource
    private TripMemberService tripMemberService;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private ItineraryCache itineraryCache;

    @Resource
    private PlatformTransactionManager transactionManager;

    /** 锁在事务之外取得，并在事务提交、缓存更新和通知完成之后释放。 */
    private <T> T executeWrite(Long tripId, Supplier<T> operation) {
        // 1、先按 tripId 加锁，避免等待锁期间建立旧的数据库快照。
        return itineraryCache.withTripLock(tripId, () -> {
            // 2、获取锁后开启独立事务，即使调用方已有事务，也在本次操作结束时提交。
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            // 3、执行写操作；成功后提交并触发回调，发生异常则回滚，最后由外层释放锁。
            return transaction.execute(status -> operation.get());
        });
    }

    @Override
    public Long add(Long tripId, String initItineraryPrompt, User loginUser) {
        // 1、参数校验：旅行 ID 不能为空且必须大于 0。
        if (tripId == null || tripId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        // 2、按 tripId 获取旅行锁，同一旅行的行程写操作串行执行。
        // 获得锁后开启新事务，执行 addLocked；事务提交、缓存刷新和通知完成后释放锁。
        return executeWrite(tripId, () -> addLocked(tripId, initItineraryPrompt, loginUser));
    }

    /** 已持有旅行锁并开启事务，完成初始化校验、AI 生成及数据保存。 */
    private Long addLocked(Long tripId, String initItineraryPrompt, User loginUser) {
        // 1、参数校验
        if(StrUtil.hasBlank(initItineraryPrompt)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程初始化提示词不能为空");
        }
        // 2、判断旅行是否存在
        Trip trip = tripService.isTripExists(tripId);
        // 3、在锁内检查是否已有行程，防止并发初始化生成多份行程。
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", trip.getId());
        long count = this.mapper.selectCountByQuery(queryWrapper);
        if(count > 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该旅行已存在行程");
        }
        // 4、判断是否有权限
        long loginUserId = loginUser.getId();
        long ownerId = trip.getOwnerId();
        if(loginUserId != ownerId){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "只有该旅行发起人才能添加行程");
        }
        // 5、使用该旅行对应的 AI Service，根据最新旅行及成员信息生成行程。
        AiItineraryGeneratorService aiItineraryGeneratorService = aiItineraryGeneratorServiceFactory.getAiItineraryGeneratorService(tripId);
        ItineraryResult itineraryResult = aiItineraryGeneratorService.generatorItinerary(tripService.getInitItineraryPrompt(tripId));
        if(itineraryResult == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "调用AI失败，请重试");
        }
        // 6、在同一事务中保存行程及明细，任一步失败都会回滚。
        Long itineraryId = addItineraryByItineraryResult(itineraryResult, trip);
        // 7、登记提交后刷新缓存，并发布业务事件；SSE 监听器在提交成功后通知前端。
        itineraryCache.refreshAfterCommit(tripId);
        eventPublisher.publishEvent(new TripItineraryChangedEvent(tripId));
        return itineraryId;
    }

    /** 沿用调用方的写事务保存 AI 结果，缓存刷新和事件发布由调用方统一处理。 */
    private Long addItineraryByItineraryResult(ItineraryResult itineraryResult, Trip trip) {
        // 1、校验 AI 返回的明细非空，且每条明细的天数均在旅行日期范围内。
        if (itineraryResult == null || itineraryResult.getItineraryItemResultList() == null
                || itineraryResult.getItineraryItemResultList().isEmpty()
                || itineraryResult.getItineraryItemResultList().stream().anyMatch(item -> item == null
                    || item.getDayNumber() == null || item.getDayNumber() < 1 || item.getDayNumber() > trip.getTravelDays())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI行程天数超出旅行日期范围，请重试");
        }
        // 2、先保存草案行程，取得明细需要关联的行程 ID。
        String title = StrUtil.blankToDefault(itineraryResult.getTitle(), trip.getTitle());
        String summary = itineraryResult.getSummary();
        BigDecimal estimatedCostPerPerson = itineraryResult.getEstimatedCostPerPerson();
        String status = ItineraryStatusEnum.DRAFT.getValue();
        long tripId = trip.getId();
        Itinerary itinerary = Itinerary.builder()
                .tripId(tripId)
                .title(title)
                .summary(summary)
                .estimatedCostPerPerson(estimatedCostPerPerson)
                .status(status)
                .build();
        boolean saveResult = this.save(itinerary);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "添加行程失败，数据库错误");
        }
        // 3、将 AI 明细转换为数据库实体，统一关联刚保存的行程。
        List<ItineraryItemResult> itineraryItemResultList = itineraryResult.getItineraryItemResultList();
        List<ItineraryItem> itineraryItemList = new ArrayList<>();
        Long itineraryId = itinerary.getId();
        for(ItineraryItemResult itineraryItemResult: itineraryItemResultList){
            Integer dayNumber = itineraryItemResult.getDayNumber();
            Integer sortOrder = itineraryItemResult.getSortOrder();
            Time startTime = itineraryItemResult.getStartTime();
            Time endTime = itineraryItemResult.getEndTime();
            String activityName = itineraryItemResult.getActivityName();
            String activityType = itineraryItemResult.getActivityType();
            String locationName = itineraryItemResult.getLocationName();
            String address = itineraryItemResult.getAddress();
            String description = itineraryItemResult.getDescription();
            BigDecimal ItemEstimatedCostPerPerson = itineraryItemResult.getEstimatedCostPerPerson();
            ItineraryItem itineraryItem = ItineraryItem.builder()
                    .itineraryId(itineraryId)
                    .dayNumber(dayNumber)
                    .sortOrder(sortOrder)
                    .startTime(startTime)
                    .endTime(endTime)
                    .activityName(activityName)
                    .activityType(activityType)
                    .locationName(locationName)
                    .address(address)
                    .description(description)
                    .estimatedCostPerPerson(ItemEstimatedCostPerPerson)
                    .build();
            itineraryItemList.add(itineraryItem);
        }
        // 4、批量保存明细，失败时抛出异常，使行程和明细一起回滚。
        boolean saveResult1 = itineraryItemService.saveBatch(itineraryItemList);
        if(!saveResult1){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "添加行程明细失败，数据库错误");
        }
        return itineraryId;
    }

    @Override
    public int confirmed(Long id, User loginUser) {
        // 1、校验行程 ID。
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程id不合法");
        }
        // 2、查询行程所属旅行，用 tripId 确定本次操作需要获取的锁。
        Itinerary itinerary = getById(id);
        if (itinerary == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程不存在或已更新，请刷新后重试");
        }
        Long tripId = itinerary.getTripId();
        // 3、获取旅行锁并开启新事务，再重新校验版本和执行确认。
        return executeWrite(tripId, () -> confirmedLocked(id, tripId, loginUser));
    }

    /** 在旅行锁和事务保护下确认当前版本，防止确认等待期间已被替换的旧行程。 */
    private int confirmedLocked(Long id, Long tripId, User loginUser) {
        // 1、重新读取当前行程，确认传入 ID 仍指向最新版本。
        Itinerary itinerary = mapper.selectOneByQuery(QueryWrapper.create().eq("tripId", tripId)
                .orderBy("createTime", false).orderBy("id", false).limit(1));
        if (itinerary == null || !Objects.equals(id, itinerary.getId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程已更新，请刷新后重试");
        }
        // 2、确认旅行存在。
        Trip trip = tripService.isTripExists(tripId);
        // 3、校验当前用户是否为旅行发起人。
        long ownerId = trip.getOwnerId();
        long loginUserId = loginUser.getId();
        if(ownerId != loginUserId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有发起人才能确认行程");
        }
        // 4、保存确认状态、确认人和确认时间。
        String status = ItineraryStatusEnum.CONFIRMED.getValue();
        LocalDateTime now = LocalDateTime.now();
        itinerary.setConfirmedBy(ownerId);
        itinerary.setStatus(status);
        itinerary.setConfirmedTime(now);
        int updateResult = this.mapper.update(itinerary);
        if(updateResult <= 0){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "确认行程失败，数据库错误");
        }
        // 5、登记提交后刷新缓存，并发布事件，由提交后监听器通知前端。
        itineraryCache.refreshAfterCommit(tripId);
        eventPublisher.publishEvent(new TripItineraryChangedEvent(tripId));
        return updateResult;
    }

    @Override
    public ItineraryVO get(Long id) {
        // 1、校验行程 ID。
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程id不合法");
        }
        // 2、查询行程所属旅行，以 tripId 读取完整行程缓存。
        Itinerary itinerary = getById(id);
        if (itinerary == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程不存在或已更新，请刷新后重试");
        }
        Long tripId = itinerary.getTripId();
        // 3、读取行程及明细；缓存未命中时由 Loader 查询数据库并回填。
        ItineraryVO snapshot = itineraryCache.get(tripId);
        // 4、校验返回的快照版本，避免旧 ID 请求拿到另一份新行程。
        if (snapshot == null || !Objects.equals(id, snapshot.getId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程已更新，请刷新后重试");
        }
        return snapshot;
    }

    @Override
    public ItineraryVO getByTripId(Long tripId, User loginUser) {
        // 1、校验旅行 ID 和登录用户。
        if (tripId == null || tripId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if (loginUser == null || loginUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 2、先校验旅行存在及用户已加入，再读取缓存，避免绕过成员权限检查。
        tripMemberService.checkMemberAccess(tripId, loginUser.getId());
        // 3、读取当前完整行程，尚未生成行程时返回 null。
        return itineraryCache.get(tripId);
    }

    @Override
    public long update(Long id, List<TripMessageAiVO> messageList, User loginuser) {
        // 1、校验行程 ID。
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程id不合法");
        }
        // 2、查询所属旅行，仅用于确定锁的 tripId；获得锁后还需重新校验版本。
        Itinerary itinerary = getById(id);
        if (itinerary == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程不存在或已更新，请刷新后重试");
        }
        Long tripId = itinerary.getTripId();
        // 3、获取旅行锁并开启新事务，串行执行本次行程更新。
        return executeWrite(tripId, () -> updateLocked(id, tripId, messageList, loginuser));
    }

    /** 在旅行锁和事务内应用选中的建议，用 AI 生成的新行程整体替换旧行程。 */
    private long updateLocked(Long id, Long tripId, List<TripMessageAiVO> messageList, User loginuser) {
        // 1、获得锁后重新检查数据库中的当前版本，拒绝更新已被替换的旧行程。
        Itinerary itinerary = mapper.selectOneByQuery(QueryWrapper.create().eq("tripId", tripId)
                .orderBy("createTime", false).orderBy("id", false).limit(1));
        if (itinerary == null || !Objects.equals(id, itinerary.getId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程已更新，请刷新后重试");
        }
        // 从缓存读取完整行程及明细，并确认缓存中的版本与本次请求一致。
        ItineraryVO itineraryVO = itineraryCache.get(tripId);
        if (itineraryVO == null || !Objects.equals(id, itineraryVO.getId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程已更新，请刷新后重试");
        }
        // 2、确认旅行存在。
        Trip trip = tripService.isTripExists(tripId);
        // 3、校验当前用户是否为旅行发起人。
        long ownerId = trip.getOwnerId();
        long loginUserId = loginuser.getId();
        if(ownerId != loginUserId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有发起人才能确认行程");
        }
        // 4、读取选中建议及其对应的用户原始问题，校验并标记建议为已应用
        StringBuilder updatePrompt = new StringBuilder(tripService.getInitItineraryPrompt(tripId)).append("\n");
        if(messageList != null && !messageList.isEmpty()){
            if (messageList.stream().anyMatch(message -> message == null
                    || message.getId() == null || message.getId() <= 0)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "建议消息id不合法");
            }
            List<Long> messageIds = messageList.stream().map(TripMessageAiVO::getId).distinct().toList();
            // 此方法会校验旅行归属、AI角色、撤回状态及待处理状态；后续失败由当前事务回滚。
            tripMessageService.updateChangeStatusByIds(tripId, messageIds, TripMessageChangeStatusEnum.APPLIED.getValue(), loginuser);
            // 正文和关联关系均以数据库为准，不使用前端传来的建议正文或时间。
            List<TripMessage> aiMessages = tripMessageService.getMapper().selectListByQuery(new QueryWrapper()
                    .in("id", messageIds)
                    .eq("tripId", tripId)
                    .eq("role", TripMessageRoleEnum.ASSISTANT.getValue())
                    .eq("recallStatus", TripMessageRecallStatusEnum.NORMAL.getValue())
                    .orderBy("createTime", true).orderBy("id", true));
            if (aiMessages.size() != messageIds.size()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "部分建议消息已不可用，请刷新后重试");
            }
            List<Long> questionIds = aiMessages.stream().map(TripMessage::getReplyToId)
                    .filter(Objects::nonNull).distinct().toList();
            Map<Long, TripMessage> questions = questionIds.isEmpty() ? Map.of()
                    : tripMessageService.getMapper().selectListByQuery(new QueryWrapper()
                            .in("id", questionIds)
                            .eq("tripId", tripId)
                            .eq("role", TripMessageRoleEnum.USER.getValue())
                            .eq("recallStatus", TripMessageRecallStatusEnum.NORMAL.getValue()))
                    .stream().collect(Collectors.toMap(TripMessage::getId, Function.identity()));
            // 与旅行成员背景使用相同的昵称，避免逐条查询用户资料。
            Map<Long, String> nicknames = tripMemberService.toMemberVOs(
                            tripMemberService.list(new QueryWrapper().eq("tripId", tripId)))
                    .stream().collect(Collectors.toMap(TripMemberVO::getUserId,
                            member -> StrUtil.blankToDefault(member.getNickname(), "未知成员"),
                            (first, second) -> first));
            updatePrompt.append("用户的建议如下（每组包含原始问题和对应的AI建议）：\n");
            for (int i = 0; i < aiMessages.size(); i++) {
                TripMessage aiMessage = aiMessages.get(i);
                TripMessage question = aiMessage.getReplyToId() == null ? null : questions.get(aiMessage.getReplyToId());
                updatePrompt.append(String.format("【建议%d】\n", i + 1));
                if (question != null && StrUtil.isNotBlank(question.getContent())) {
                    updatePrompt.append(String.format("用户id：%d\n用户昵称：%s\n提问时间：%s\n用户原始问题：\n%s\n",
                            question.getSenderId(),
                            nicknames.getOrDefault(question.getSenderId(), "未知成员"),
                            question.getCreateTime(), question.getContent()));
                } else {
                    updatePrompt.append("用户原始问题：不可用（未关联、已删除、已撤回或内容为空），请勿推测原文。\n");
                }
                updatePrompt.append(String.format("AI回复时间：%s\nAI建议：\n%s\n\n",
                        aiMessage.getCreateTime(), aiMessage.getContent()));
            }
        }
        // 5、将当前行程加入提示词；更新仅使用选中的建议问答，不读取最近聊天历史。
        String itineraryInfos = this.getItineraryInfosPrompt(itineraryVO);
        if(itineraryInfos == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成更新提示词失败");
        }
        updatePrompt.append(itineraryInfos);
        // 6、调用ai获取行程和行程明细
        AiItineraryGeneratorService aiItineraryGeneratorService = aiItineraryGeneratorServiceFactory.getAiItineraryGeneratorService(tripId);
        ItineraryResult itineraryUpdateResult = aiItineraryGeneratorService.updateItinerary(updatePrompt.toString());
        // 7、在当前事务内删除旧行程及明细，后续保存失败时一起回滚。
        deleteRows(id);
        // 8、保存新行程及明细，返回新行程 ID。
        Long newItineraryId = this.addItineraryByItineraryResult(itineraryUpdateResult, trip);
        // 9、登记提交后刷新缓存，完整替换只发布一次事件，提交成功后通知前端。
        itineraryCache.refreshAfterCommit(tripId);
        eventPublisher.publishEvent(new TripItineraryChangedEvent(tripId));
        return newItineraryId;
    }

    @Override
    public String getItineraryInfosPrompt(ItineraryVO itineraryVO) {

        // 1、将行程标题、概述和预计费用拼接为 AI 可读取的背景文本。
        StringBuilder itineraryInfosPrompt = new StringBuilder();

        itineraryInfosPrompt.append(String.format("""
            【当前行程信息】
            
            行程标题：%s
            
            行程概述：
            %s
            
            每人人均预计费用：
            %s元
            
            【当前行程安排】
            """,
                itineraryVO.getTitle(),
                itineraryVO.getSummary(),
                itineraryVO.getEstimatedCostPerPerson() == null ?
                        "暂无" :
                        itineraryVO.getEstimatedCostPerPerson().toPlainString()
        ));

        // 2、按明细列表的现有顺序追加安排，未知结束时间和费用显示为“暂无”。
        List<ItineraryItemVO> itineraryItemVOList = itineraryVO.getItineraryItemVOList();

        for (ItineraryItemVO item : itineraryItemVOList) {

            itineraryInfosPrompt.append(String.format("""
                
                第%d天
                第%d个行程
                
                时间：
                %s - %s
                
                活动名称：
                %s
                
                活动类型：
                %s
                
                地点：
                %s
                
                地址：
                %s
                
                活动说明：
                %s
                
                预计人均费用：
                %s元
                
                """,
                    item.getDayNumber(),
                    item.getSortOrder(),
                    item.getStartTime(),
                    item.getEndTime() == null ? "暂无" : item.getEndTime(),
                    item.getActivityName(),
                    item.getActivityType(),
                    item.getLocationName(),
                    item.getAddress(),
                    item.getDescription(),
                    item.getEstimatedCostPerPerson() == null ?
                            "暂无" :
                            item.getEstimatedCostPerPerson().toPlainString()
            ));
        }
        return itineraryInfosPrompt.toString();
    }

    /** 仅执行数据库删除，沿用调用方事务，不单独刷新缓存或发布事件。 */
    private boolean deleteRows(Long id) {
        // 1、检验参数
        if(id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id不合法");
        }
        // 2、删除行程明细
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("itineraryId", id);
        int deleteItemResult = itineraryItemService.getMapper().deleteByQuery(queryWrapper);
        if(deleteItemResult <= 0){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除行程明细失败，数据库错误");
        }
        // 3、删除行程
        boolean deleteResult = this.removeById(id);
        if(!deleteResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除行程失败，数据库错误");
        }
        return true;
    }

    @Override
    public Long getIdByTripId(Long tripId) {
        // 1、参数校验
        if(tripId == null || tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不存在");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、读取当前完整行程并返回 ID，尚未生成行程时返回 null。
        ItineraryVO snapshot = itineraryCache.get(tripId);
        return snapshot == null ? null : snapshot.getId();
    }
}
