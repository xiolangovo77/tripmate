package com.ecnu.tripmate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.ecnu.tripmate.ai.guardrail.TripCityValidator;
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
import java.util.Objects;
import com.ecnu.tripmate.model.dto.trip.TripAddRequest;
import com.ecnu.tripmate.model.dto.trip.TripUpdateRequest;
import com.ecnu.tripmate.model.dto.trip.TripScheduleRequest;
import com.ecnu.tripmate.mapper.ItineraryMapper;
import com.ecnu.tripmate.util.TripScheduleValidator;

/**
 * 旅行表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class TripServiceImpl extends ServiceImpl<TripMapper, Trip>  implements TripService{

    @Resource
    private TripMemberService tripMemberService;

    @Resource
    private ItineraryMapper itineraryMapper;

    @Resource
    private TripCityValidator tripCityValidator;

    @Override
    @Transactional
    public Long addTrip(TripAddRequest request, User loginuser) {
        int travelDays = TripScheduleValidator.validateAndNormalize(request);
        String destination = request.getDestination();
        BigDecimal budgetLimit = request.getBudgetLimit();
        if(budgetLimit == null || budgetLimit.compareTo(BigDecimal.ZERO) <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "预算金额必须为正数");
        }
        // 在写入旅行和发起人成员记录之前检查城市真实性。
        tripCityValidator.validate(request.getDepartureLocation(), destination);
        // 2、设置默认值
        long ownerId = loginuser.getId();
        String defaultTitle = String.format("%s%d日游", destination, travelDays);
        if (defaultTitle.length() > 120) defaultTitle = defaultTitle.substring(0, 120);
        String defaultStatus = TripStatusEnum.PREPARING.getValue();
        // 3、插入数据
        Trip trip = Trip.builder()
                .ownerId(ownerId)
                .title(defaultTitle)
                .departureLocation(request.getDepartureLocation())
                .departureDate(request.getDepartureDate())
                .departurePeriod(request.getDeparturePeriod())
                .returnDate(request.getReturnDate())
                .returnPeriod(request.getReturnPeriod())
                .destination(destination)
                .travelDays(travelDays)
                .status(defaultStatus)
                .build();
        boolean addResult = this.save(trip);
        if(!addResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "加入旅行失败，数据库错误");
        }
        // 4、同步插入trip_member表
        tripMemberService.add(trip.getId(), TripMemberRoleEnum.OWNER.getValue(), null, loginuser, budgetLimit);
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
    public boolean update(TripUpdateRequest request, User loginuser) {
        int travelDays = TripScheduleValidator.validateAndNormalize(request);
        if (request.getId() == null || request.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if (StrUtil.isBlank(request.getTitle()) || request.getTitle().trim().length() > 120) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题不能为空且不能超过120个字符");
        }
        Trip trip = isTripExists(request.getId());
        checkTripOwner(trip, loginuser);
        boolean scheduleChanged = !Objects.equals(trip.getDepartureLocation(), request.getDepartureLocation())
                || !Objects.equals(trip.getDestination(), request.getDestination())
                || !Objects.equals(trip.getDepartureDate(), request.getDepartureDate())
                || !Objects.equals(trip.getReturnDate(), request.getReturnDate())
                || !Objects.equals(trip.getDeparturePeriod(), request.getDeparturePeriod())
                || !Objects.equals(trip.getReturnPeriod(), request.getReturnPeriod());
        if (scheduleChanged && hasItinerary(trip.getId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "已有行程，不能直接修改路线或出行日期、时段，请新建旅行重新规划");
        }
        // 允许修改路线时也检查新城市，避免创建后通过编辑绕过校验。
        // 仅修改标题、日期或时段时，不重复调用检查模型。
        if (!Objects.equals(trip.getDepartureLocation(), request.getDepartureLocation())
                || !Objects.equals(trip.getDestination(), request.getDestination())) {
            tripCityValidator.validate(request.getDepartureLocation(), request.getDestination());
        }
        BeanUtil.copyProperties(request, trip);
        trip.setTitle(request.getTitle().trim());
        trip.setTravelDays(travelDays);
        boolean updateResult = this.updateById(trip);
        if(!updateResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新状态失败，数据库错误");
        }
        return updateResult;
    }

    @Override
    public Trip isTripExists(long tripId){
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        Trip trip = this.getById(tripId);
        if(trip == null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "旅行不存在，请创建");
        }
        return trip;
    }

    @Override
    public void checkTripOwner(Trip trip, User loginUser) {
        Long ownerId = trip.getOwnerId();
        Long loginUserId = loginUser.getId();
        if(!ownerId.equals(loginUserId)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有旅行发起人才能执行此操作");
        }
    }

    private boolean hasItinerary(long tripId) {
        return itineraryMapper.selectCountByQuery(QueryWrapper.create().eq("tripId", tripId)) > 0;
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
        tripVO.setHasItinerary(hasItinerary(tripId));
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
        int calculatedDays = TripScheduleValidator.validateAndNormalize(BeanUtil.copyProperties(trip, TripScheduleRequest.class));
        if (!Objects.equals(trip.getTravelDays(), calculatedDays)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行日期与天数不一致，请先完善旅行信息");
        }
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
            出发地：%s
            目的地：%s
            出发日期：%s
            出发时段：%s
            返回日期（到达出发地）：%s
            返回时段：%s
            旅行天数：%d天
            旅行人数：%d人

            【旅行成员信息】
            """, trip.getTitle(), trip.getDepartureLocation(), trip.getDestination(), trip.getDepartureDate(),
                trip.getDeparturePeriod(), trip.getReturnDate(), trip.getReturnPeriod(), trip.getTravelDays(), tripMemberList.size()));
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
                .like("departureLocation", tripPageRequest.getDepartureLocation())
                .eq("departureDate", tripPageRequest.getDepartureDate())
                .eq("returnDate", tripPageRequest.getReturnDate())
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
