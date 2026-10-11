package com.ecnu.tripmate.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.TravelPaceEnum;
import com.ecnu.tripmate.model.enums.trip.TripMemberRoleEnum;
import com.ecnu.tripmate.model.enums.trip.TripMemberStatusEnum;
import com.ecnu.tripmate.model.enums.trip.TripStatusEnum;
import com.ecnu.tripmate.service.TripService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.TripMember;
import com.ecnu.tripmate.mapper.TripMemberMapper;
import com.ecnu.tripmate.service.TripMemberService;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 旅行成员表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class TripMemberServiceImpl extends ServiceImpl<TripMemberMapper, TripMember>  implements TripMemberService{

    @Resource
    @Lazy
    private TripService tripService;

    @Override
    public Long add(long tripId, String role, User logiguser, BigDecimal budgetLimit) {
        // 1、校验参数
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(role == null || TripMemberRoleEnum.getEnumByValue(role) == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行成员角色不合法");
        }
        if(budgetLimit == null || budgetLimit.compareTo(BigDecimal.ZERO) <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "预算金额必须为正数");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、如果是发起人，需要判断是否为本场旅行的唯一发起人
        String owner = TripMemberRoleEnum.OWNER.getValue();
        if(owner.equals(role)){
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq("tripId", tripId)
                    .eq("role", owner);
            long count = this.mapper.selectCountByQuery(queryWrapper);
            if(count > 0){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "该旅行已存在发起人");
            }
        }
        // 4、是否重复加入旅行
        QueryWrapper queryWrapper1 = new QueryWrapper();
        queryWrapper1.eq("tripId", tripId)
                .eq("userId", logiguser.getId());
        long count1 = this.mapper.selectCountByQuery(queryWrapper1);
        if(count1 > 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "您已加入该旅行");
        }
        // 5、设置默认值
        Long userId = logiguser.getId();
        String nickname = logiguser.getNickname();
        String travelPace = logiguser.getTravelPace();
        String status = TripMemberStatusEnum.JOINED.getValue();
        String favoriteActivities = logiguser.getFavoriteActivities();
        LocalDateTime joinedTime = LocalDateTime.now();
        // 6、插入数据
        TripMember tripMember = TripMember.builder()
                .tripId(tripId)
                .userId(userId)
                .nickname(nickname)
                .role(role)
                .budgetLimit(budgetLimit)
                .travelPace(travelPace)
                .favoriteActivities(favoriteActivities)
                .status(status)
                .joinedTime(joinedTime)
                .build();
        boolean saveResult = this.save(tripMember);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "加入成员失败，数据库错误");
        }
        return tripMember.getTripId();
    }

    @Override
    public boolean updateTripMemberStatus(long tripId,long userId, String status, User loginuser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(status == null || TripMemberStatusEnum.getEnumByValue(status) == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行成员状态不合法");
        }
        // 2、判断旅行成员是否存在
        TripMember tripMember = getTripMember(tripId, userId);
        // 3、判断是否有权限
        long loginUserId = loginuser.getId();
        if(loginUserId != userId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 4、如果为已加入，要更新joinedTime
        if(TripMemberStatusEnum.JOINED.getValue().equals(status)){
            tripMember.setJoinedTime(LocalDateTime.now());
        }
        // 5、更新状态
        tripMember.setStatus(status);
        boolean updateResult = this.updateById(tripMember);
        if(!updateResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新状态失败，数据库错误");
        }
        return updateResult;
    }

    /**
     * 判断旅行成员是否存在
     * @param tripId 旅行id
     * @param userId 用户id
     * @return
     */
    private TripMember getTripMember(long tripId, long userId) {
        tripService.isTripExists(tripId);
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", userId);
        TripMember tripMember = this.mapper.selectOneByQuery(queryWrapper);
        if(tripMember == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先加入旅行");
        }
        return tripMember;
    }

    @Override
    public boolean update(long tripId, long userId, BigDecimal budgetLimit, String travelPace, String favoriteActivities, User loginuser) {
        // 1、参数校验
        if(budgetLimit == null || budgetLimit.compareTo(BigDecimal.ZERO) <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "预算金额必须为正数");
        }
        if(travelPace == null || TravelPaceEnum.getEnumByValue(travelPace) == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请选择正确的旅行节奏");
        }
        try {
            JSONArray jsonArray = JSONUtil.parseArray(favoriteActivities);
            if (jsonArray.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "活动偏好不能为空");
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "活动偏好格式错误");
        }
        // 2、判断旅行成员是否存在
        TripMember tripMember = this.getTripMember(tripId, userId);
        // 3、判断是否有权限
        long loginUserId = loginuser.getId();
        if(loginUserId != userId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 4、更新数据库
        tripMember.setBudgetLimit(budgetLimit);
        tripMember.setTravelPace(travelPace);
        tripMember.setFavoriteActivities(favoriteActivities);
        boolean updateResult = this.updateById(tripMember);
        if(!updateResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新状态失败，数据库错误");
        }
        return updateResult;
    }
}
