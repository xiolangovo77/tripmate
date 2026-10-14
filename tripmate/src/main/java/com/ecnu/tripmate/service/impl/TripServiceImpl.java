package com.ecnu.tripmate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.trip.TripPageRequest;
import com.ecnu.tripmate.model.entity.TripMember;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.trip.TripMemberRoleEnum;
import com.ecnu.tripmate.model.enums.trip.TripMemberStatusEnum;
import com.ecnu.tripmate.model.enums.trip.TripStatusEnum;
import com.ecnu.tripmate.model.vo.trip.TripMemberVO;
import com.ecnu.tripmate.model.vo.trip.TripPageVO;
import com.ecnu.tripmate.model.vo.trip.TripVO;
import com.ecnu.tripmate.service.TripMemberService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.mapper.TripMapper;
import com.ecnu.tripmate.service.TripService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 旅行表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class TripServiceImpl extends ServiceImpl<TripMapper, Trip>  implements TripService{

    @Resource
    private TripMemberService tripMemberService;

    @Override
    @Transactional
    public Long addTrip(String destination, Integer travelDays, BigDecimal budgetLimit, User loginuser) {
        // 1、校验参数
        if(StrUtil.hasBlank(destination)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "目的地不能为空");
        }
        if(travelDays == null || travelDays <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行天数必须为正数");
        }
        if(budgetLimit == null || budgetLimit.compareTo(BigDecimal.ZERO) <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "预算金额必须为正数");
        }
        // 2、设置默认值
        long ownerId = loginuser.getId();
        String defaultTitle = String.format("%s%d日游", destination, travelDays);
        String defaultStatus = TripStatusEnum.PREPARING.getValue();
        // 3、插入数据
        Trip trip = Trip.builder()
                .ownerId(ownerId)
                .title(defaultTitle)
                .destination(destination)
                .travelDays(travelDays)
                .status(defaultStatus)
                .build();
        boolean addResult = this.save(trip);
        if(!addResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "加入旅行失败，数据库错误");
        }
        // 4、同步插入trip_member表
        tripMemberService.add(trip.getId(), TripMemberRoleEnum.OWNER.getValue(),loginuser, budgetLimit);
        return trip.getId();
    }

    @Override
    public boolean updateTripStatus(long tripId, String status, User loginuser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(status == null || TripStatusEnum.getEnumByValue(status) == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行状态不合法");
        }
        // 2、判断旅行是否存在
        Trip trip = isTripExists(tripId);
        // 3、判断是否有权限
        checkTripOwner(trip, loginuser);
        // 4、更新状态
        trip.setStatus(status);
        boolean updateResult = this.updateById(trip);
        if(!updateResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新状态失败，数据库错误");
        }
        return updateResult;
    }

    @Override
    public boolean update(long tripId, String title, String destination, Integer travelDays, User loginuser) {
        // 1、校验参数
        if(StrUtil.hasBlank(title, destination)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题、目的地不能为空");
        }
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(travelDays == null || travelDays <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行天数必须为正数");
        }
        // 2、判断旅行是否存在
        Trip trip = isTripExists(tripId);
        // 3、判断是否有权限
        checkTripOwner(trip, loginuser);
        // 4、更新状态
        trip.setTitle(title);
        trip.setDestination(destination);
        trip.setTravelDays(travelDays);
        boolean updateResult = this.updateById(trip);
        if(!updateResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新状态失败，数据库错误");
        }
        return updateResult;
    }

    /**
     * 判断旅行是否存在
     * @param tripId 旅行id
     * @return
     */
    @Override
    public Trip isTripExists(long tripId){
        Trip trip = this.getById(tripId);
        if(trip == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行不存在，请创建");
        }
        return trip;
    }

    /**
     * 校验用户是否为旅行发起人
     *
     * @param trip 旅行信息
     * @param loginUser 当前登录用户
     */
    @Override
    public void checkTripOwner(Trip trip, User loginUser) {
        Long ownerId = trip.getOwnerId();
        Long loginUserId = loginUser.getId();
        if(!ownerId.equals(loginUserId)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有旅行发起人才能执行此操作");
        }
    }

    @Override
    public TripVO getTripById(long tripId) {
        // 1、校验参数
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不存在");
        }
        // 2、获取旅行信息
        Trip trip = isTripExists(tripId);
        // 3、获取旅行人员信息
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("status", TripMemberStatusEnum.JOINED.getValue());
        List<TripMember> tripMembers = tripMemberService.getMapper().selectListByQuery(queryWrapper);
        if(tripMembers == null || tripMembers.isEmpty()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不存在旅行人员");
        }
        List<TripMemberVO> tripMembersVO = BeanUtil.copyToList(tripMembers, TripMemberVO.class);
        // 4、返回数据
        TripVO tripVO = new TripVO();
        BeanUtil.copyProperties(trip, tripVO);
        tripVO.setTripMembers(tripMembersVO);
        return tripVO;
    }

    @Override
    public String getInitItineraryPrompt(Long tripId) {
        // 1、校验参数
        if(tripId == null || tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不存在");
        }
        // 2、获取旅行信息
        Trip trip = isTripExists(tripId);
        // 3、获取旅行成员信息
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("status", TripMemberStatusEnum.JOINED.getValue());
        List<TripMember> tripMemberList = tripMemberService.getMapper().selectListByQuery(queryWrapper);
        if(tripMemberList == null || tripMemberList.isEmpty()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该旅行没有旅行成员");
        }
        // 4、生成旅行基本信息
        StringBuilder initItineraryPrompt = new StringBuilder();
        initItineraryPrompt.append(String.format("""
            【旅行基本信息】
            旅行标题：%s
            目的地：%s
            旅行天数：%d天
            旅行人数：%d人

            【旅行成员信息】
            """, trip.getTitle(), trip.getDestination(), trip.getTravelDays(), tripMemberList.size()));
        // 5、生成旅行成员信息
        for(int i = 0; i < tripMemberList.size(); i++){
            TripMember tripMember = tripMemberList.get(i);
            String nickname = tripMember.getNickname();
            BigDecimal budgetLimit = tripMember.getBudgetLimit();
            String travelPace = tripMember.getTravelPace();
            String favoriteActivities = tripMember.getFavoriteActivities();
            String budget = budgetLimit.toPlainString();
            initItineraryPrompt.append(String.format("""

                成员%d：
                名字：%s
                预算：%s
                节奏偏好：%s
                活动偏好：%s
                """, i + 1, nickname, budget, travelPace, favoriteActivities));
        }
        return initItineraryPrompt.toString();
    }

    @Override
    public List<TripPageVO> page(TripPageRequest tripPageRequest, User loginUser) {
        // 1、获取请求体的参数
        String title = tripPageRequest.getTitle();
        String destination = tripPageRequest.getDestination();
        Integer travelDays = tripPageRequest.getTravelDays();
        String status = tripPageRequest.getStatus();
        LocalDateTime beginTime = tripPageRequest.getBeginTime();
        LocalDateTime endTime = tripPageRequest.getEndTime();
        int pageNum = tripPageRequest.getPageNum();
        int pageSize = tripPageRequest.getPageSize();
        String sortField = tripPageRequest.getSortField();
        String sortOrder = tripPageRequest.getSortOrder();
        // 2、获取该用户的所有已加入的旅行id
        long userId = loginUser.getId();
        List<Long> tripIds = tripMemberService.getTripIdsByUserId(userId);
        if(tripIds == null || tripIds.isEmpty()){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "不存在旅行信息");
        }
        // 3、设置查询条件
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.in("id", tripIds)
                .like("title", title)
                .like("destination", destination)
                .eq("travelDays", travelDays)
                .eq("status", status)
                .gt("createTime", beginTime)
                .lt("createTime", endTime);
        if(sortField == null){
            // 默认按照时间降序排列
            queryWrapper.orderBy("createTime", false);
        }else{
            queryWrapper.orderBy(sortField, sortOrder.equals("ascend"));
        }
        // 4、查询数据
        Page<Trip> page = this.page(Page.of(pageNum, pageSize), queryWrapper);
        if(page == null || page.getPageSize() == 0){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "不存在旅行信息");
        }
        // 5、返回数据
        List<TripPageVO> tripPageVOList = BeanUtil.copyToList(page.getRecords(), TripPageVO.class);
        return tripPageVOList;
    }
}
