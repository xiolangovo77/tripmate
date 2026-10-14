package com.ecnu.tripmate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.ecnu.tripmate.ai.AiItineraryGeneratorService;
import com.ecnu.tripmate.ai.model.ItineraryItemResult;
import com.ecnu.tripmate.ai.model.ItineraryResult;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.ItineraryItem;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.ItineraryStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageChangeStatusEnum;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryItemVO;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.ecnu.tripmate.service.ItineraryItemService;
import com.ecnu.tripmate.service.TripService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.Itinerary;
import com.ecnu.tripmate.mapper.ItineraryMapper;
import com.ecnu.tripmate.service.ItineraryService;
import jakarta.annotation.Resource;
import org.springframework.cglib.core.Local;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
    private AiItineraryGeneratorService aiItineraryGeneratorService;

    @Resource
    private ItineraryItemService itineraryItemService;

    @Resource
    private TripMessageServiceImpl tripMessageService;

    @Override
    @Transactional
    public Long add(Long tripId, String initItineraryPrompt, User loginUser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(StrUtil.hasBlank(initItineraryPrompt)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程初始化提示词不能为空");
        }
        // 2、判断旅行是否存在
        Trip trip = tripService.isTripExists(tripId);
        // 3、判断该旅行是否存在行程
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
        // 5、调用ai获取行程和行程明细
        ItineraryResult itineraryResult = aiItineraryGeneratorService.generatorItinerary(initItineraryPrompt);
        if(itineraryResult == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "调用AI失败，请重试");
        }
        return addItineraryByItineraryResult(itineraryResult, trip);
    }

    private Long addItineraryByItineraryResult(ItineraryResult itineraryResult, Trip trip) {
        // 数据持久化
        // 添加行程
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
        // 添加行程明细
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
        boolean saveResult1 = itineraryItemService.saveBatch(itineraryItemList);
        if(!saveResult1){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "添加行程明细失败，数据库错误");
        }
        return itineraryId;
    }

    @Override
    public int confirmed(Long id, User loginUser) {
        // 1、参数校验
        if(id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程id不合法");
        }
        // 2、判断行程是否存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("id" ,id);
        Itinerary itinerary = this.getById(id);
        if(itinerary == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程不存在");
        }
        // 3、判断旅行、是否存在
        Long tripId = itinerary.getTripId();
        Trip trip = tripService.isTripExists(tripId);
        // 4、判断是否有权限
        long ownerId = trip.getOwnerId();
        long loginUserId = loginUser.getId();
        if(ownerId != loginUserId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有发起人才能确认行程");
        }
        // 5、设置参数
        String status = ItineraryStatusEnum.CONFIRMED.getValue();
        LocalDateTime now = LocalDateTime.now();
        itinerary.setConfirmedBy(ownerId);
        itinerary.setStatus(status);
        itinerary.setConfirmedTime(now);
        int updateResult = this.mapper.update(itinerary);
        if(updateResult <= 0){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "确认行程失败，数据库错误");
        }
        return updateResult;
    }

    @Override
    public ItineraryVO get(Long id) {
        // 1、参数校验
        if(id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程id不合法");
        }
        // 2、判断行程是否存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("id" ,id);
        Itinerary itinerary = this.getById(id);
        if(itinerary == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "行程不存在");
        }
        // 3、获取行程明细
        QueryWrapper queryWrapper1 = new QueryWrapper();
        queryWrapper1.eq("itineraryId", id);
        List<ItineraryItem> itineraryItemList = itineraryItemService.getMapper().selectListByQuery(queryWrapper1);
        if(itineraryItemList == null || itineraryItemList.isEmpty()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不存在行程明细");
        }
        // 4、封装结果返回
        ItineraryVO itineraryVO = new ItineraryVO();
        BeanUtil.copyProperties(itinerary, itineraryVO);
        List<ItineraryItemVO> itineraryItemVOList = BeanUtil.copyToList(itineraryItemList, ItineraryItemVO.class);
        itineraryVO.setItineraryItemVOList(itineraryItemVOList);
        return itineraryVO;
    }

    @Override
    @Transactional
    public long update(Long id, List<TripMessageAiVO> messageList, User loginuser) {
        // 1、获取行程和行程明细
        ItineraryVO itineraryVO = this.get(id);
        // 2、判断旅行、是否存在
        Long tripId = itineraryVO.getTripId();
        Trip trip = tripService.isTripExists(tripId);
        // 3、判断是否有权限
        long ownerId = trip.getOwnerId();
        long loginUserId = loginuser.getId();
        if(ownerId != loginUserId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有发起人才能确认行程");
        }
        // 4、如果存在ai消息，将其改成已应用，并获取ai消息内容
        StringBuilder updatePrompt = new StringBuilder();
        if(messageList != null && !messageList.isEmpty()){
            List<Long> messageIds = messageList.stream().map(TripMessageAiVO::getId).toList();
            String messageInfos = messageList.stream()
                    .map(message -> String.format("消息时间：%s 消息内容%s",
                            message.getCreateTime(), message.getContent()))
                            .collect(Collectors.joining("\n"));
            updatePrompt.append("用户的建议如下：\n").append(messageInfos).append("\n");
            tripMessageService.updateChangeStatusByIds(tripId, messageIds, TripMessageChangeStatusEnum.APPLIED.getValue(), loginuser);
        }
        // 5、获得更新行程的提示词
        ItineraryResult itineraryResult = new ItineraryResult();
        BeanUtil.copyProperties(itineraryVO, itineraryResult);
        List<ItineraryItemVO> itineraryItemList = itineraryVO.getItineraryItemVOList();
        List<ItineraryItemResult> itineraryItemResultList = BeanUtil.copyToList(itineraryItemList, ItineraryItemResult.class);
        itineraryResult.setItineraryItemResultList(itineraryItemResultList);
        String itineraryInfos = this.getItineraryInfosPrompt(itineraryResult);
        if(itineraryInfos == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成更新提示词失败");
        }
        updatePrompt.append(itineraryInfos);
        // 6、调用ai获取行程和行程明细
        ItineraryResult itineraryUpdateResult = aiItineraryGeneratorService.updateItinerary(updatePrompt.toString());
        // 7、删除原来的行程和行程明细
        this.delete(id);
        // 8、数据持久化
        return this.addItineraryByItineraryResult(itineraryUpdateResult, trip);
    }

    @Override
    public String getItineraryInfosPrompt(ItineraryResult itineraryResult) {

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
                itineraryResult.getTitle(),
                itineraryResult.getSummary(),
                itineraryResult.getEstimatedCostPerPerson() == null ?
                        "暂无" :
                        itineraryResult.getEstimatedCostPerPerson().toPlainString()
        ));

        List<ItineraryItemResult> itineraryItemResultList =
                itineraryResult.getItineraryItemResultList();

        for (ItineraryItemResult item : itineraryItemResultList) {

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

    @Override
    @Transactional
    public boolean delete(Long id) {
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
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不存在");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、查询数据库
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId);
        List<Itinerary> itineraries = this.mapper.selectListByQuery(queryWrapper);
        if(itineraries == null || itineraries.isEmpty()){
            return null;
        }
        return itineraries.getFirst().getId();
    }
}
