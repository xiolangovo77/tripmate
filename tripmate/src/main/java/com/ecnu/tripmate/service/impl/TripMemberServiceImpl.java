package com.ecnu.tripmate.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import cn.hutool.core.bean.BeanUtil;
import com.ecnu.tripmate.model.vo.trip.TripMemberVO;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.TravelPaceEnum;
import com.ecnu.tripmate.model.enums.trip.TripMemberRoleEnum;
import com.ecnu.tripmate.model.enums.trip.TripMemberStatusEnum;
import com.ecnu.tripmate.model.enums.trip.TripStatusEnum;
import com.ecnu.tripmate.service.TripInvitationService;
import com.ecnu.tripmate.service.TripService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.TripMember;
import com.ecnu.tripmate.mapper.TripMemberMapper;
import com.ecnu.tripmate.mapper.UserMapper;
import com.ecnu.tripmate.service.TripMemberService;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import com.ecnu.tripmate.event.TripMembersChangedEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    @Resource
    private TripInvitationService tripInvitationService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Override
    public List<TripMemberVO> getActiveMembers(long tripId, long userId) {
        tripService.isTripExists(tripId);
        List<TripMember> current = this.mapper.selectListByQuery(activeMembers(tripId)
                .orderBy("joinedTime", true).orderBy("id", true));
        if (current.stream().noneMatch(member -> member.getUserId().equals(userId))) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "你已不在此旅行小队中");
        }
        return toMemberVOs(current);
    }

    @Override
    public List<TripMemberVO> toMemberVOs(List<TripMember> members) {
        List<TripMemberVO> result = BeanUtil.copyToList(members, TripMemberVO.class);
        List<Long> userIds = members.stream().map(TripMember::getUserId)
                .filter(Objects::nonNull).distinct().toList();
        if (userIds.isEmpty()) {
            return result;
        }
        Map<Long, User> users = userMapper.selectListByQuery(QueryWrapper.create()
                        .select("id", "avatarUrl").in("id", userIds))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        for (TripMemberVO member : result) {
            User user = users.get(member.getUserId());
            if (user != null) {
                member.setAvatarUrl(user.getAvatarUrl());
            }
        }
        return result;
    }

    @Override
    public void checkMemberAccess(long tripId, long userId) {
        tripService.isTripExists(tripId);
        if (this.mapper.selectCountByQuery(activeMembers(tripId).eq("userId", userId)) == 0) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "你已不在此旅行小队中");
        }
    }

    /**
     * 构建旅行有效成员的查询条件。
     *
     * @param tripId 旅行ID
     * @return 限定旅行ID和已加入状态的查询条件
     */
    private QueryWrapper activeMembers(long tripId) {
        return QueryWrapper.create().eq("tripId", tripId)
                .eq("status", TripMemberStatusEnum.JOINED.getValue());
    }

    @Override
    @Transactional
    public Long add(long tripId, String role, String inviteCode, User logiguser, BigDecimal budgetLimit) {
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
        }else{
            // 如果是普通成员需要检验邀请码
            tripInvitationService.checkInviteCode(tripId, inviteCode);
        }
        // 4、是否重复加入旅行
        QueryWrapper queryWrapper1 = new QueryWrapper();
        queryWrapper1.eq("tripId", tripId)
                .eq("userId", logiguser.getId())
                .eq("status", TripMemberStatusEnum.JOINED.getValue());
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
        eventPublisher.publishEvent(new TripMembersChangedEvent(tripId));
        return tripMember.getTripId();
    }

    @Override
    @Transactional
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
        eventPublisher.publishEvent(new TripMembersChangedEvent(tripId));
        return updateResult;
    }

    private TripMember getTripMember(long tripId, long userId) {
        tripService.isTripExists(tripId);
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", userId)
                .eq("status", TripMemberStatusEnum.JOINED.getValue());
        TripMember tripMember = this.mapper.selectOneByQuery(queryWrapper);
        if(tripMember == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先加入旅行");
        }
        return tripMember;
    }

    @Override
    @Transactional
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
        eventPublisher.publishEvent(new TripMembersChangedEvent(tripId));
        return updateResult;
    }

    @Override
    public List<Long> getTripIdsByUserId(long userId) {
        // 1、参数校验
        if(userId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户id不合法");
        }
        // 2、查询数据库
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userId", userId)
                .eq("status", TripMemberStatusEnum.JOINED.getValue());
        List<TripMember> tripMemberList = this.mapper.selectListByQuery(queryWrapper);
        if(tripMemberList == null || tripMemberList.isEmpty()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该用户不存在旅行信息");
        }
        // 3、处理查询结果
        List<Long> tripIds = tripMemberList.stream().map(TripMember::getTripId).toList();
        return tripIds;
    }
}
