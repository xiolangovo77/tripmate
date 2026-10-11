package com.ecnu.tripmate.service.impl;

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
import com.ecnu.tripmate.service.ItineraryItemService;
import com.ecnu.tripmate.service.TripService;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.Itinerary;
import com.ecnu.tripmate.mapper.ItineraryMapper;
import com.ecnu.tripmate.service.ItineraryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        // 3、判断是否有权限
        long loginUserId = loginUser.getId();
        long ownerId = trip.getOwnerId();
        if(loginUserId != ownerId){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "只有该旅行发起人才能添加行程");
        }
        // 4、调用ai获取行程和行程明细
        ItineraryResult itineraryResult = aiItineraryGeneratorService.generatorItinerary(initItineraryPrompt);
        if(itineraryResult == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "调用AI失败，请重试");
        }
        // 5、数据持久化
        // 5.1、添加行程
        String title = StrUtil.blankToDefault(itineraryResult.getTitle(), trip.getTitle());
        String summary = itineraryResult.getSummary();
        BigDecimal estimatedCostPerPerson = itineraryResult.getEstimatedCostPerPerson();
        String status = ItineraryStatusEnum.DRAFT.getValue();
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
        // 5.1、添加行程明细
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
}
