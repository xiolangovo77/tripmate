package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.trip.TripUpdateStatusRequest;
import com.ecnu.tripmate.model.dto.tripMember.TripMemberAddRequest;
import com.ecnu.tripmate.model.dto.tripMember.TripMemberUpdateRequest;
import com.ecnu.tripmate.model.dto.tripMember.TripMemberUpdateStatusRequest;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.trip.TripMemberRoleEnum;
import com.ecnu.tripmate.model.enums.trip.TripMemberStatusEnum;
import com.ecnu.tripmate.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecnu.tripmate.model.entity.TripMember;
import com.ecnu.tripmate.service.TripMemberService;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * 旅行成员表 控制层。
 *
 * @author Wolfcub
 */
@RestController
@RequestMapping("/tripMember")
public class TripMemberController {

    @Resource
    private TripMemberService tripMemberService;

    @Resource
    private UserService userService;

    /**
     * 修改旅行成员状态
     * @param tripMemberUpdateStatusRequest 修改旅行成员状态请求
     * @param request
     * @return
     */
    @PostMapping("updateStatus")
    public BaseResponse<Boolean> updateStatus(@RequestBody TripMemberUpdateStatusRequest tripMemberUpdateStatusRequest, HttpServletRequest request){
        if(tripMemberUpdateStatusRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        long tripId = tripMemberUpdateStatusRequest.getTripId();
        long userId = tripMemberUpdateStatusRequest.getUserId();
        String status = tripMemberUpdateStatusRequest.getStatus();
        boolean updateResult = tripMemberService.updateTripMemberStatus(tripId, userId, status, loginUser);
        return ResultUtils.success(updateResult);
    }

    /**
     * 更新旅行用户信息
     * @param tripMemberUpdateRequest 更新旅行用户信息请求
     * @param request
     * @return
     */
    @PostMapping("update")
    public BaseResponse<Boolean> update(@RequestBody TripMemberUpdateRequest tripMemberUpdateRequest, HttpServletRequest request){
        if(tripMemberUpdateRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        long tripId = tripMemberUpdateRequest.getTripId();
        long userId = tripMemberUpdateRequest.getUserId();
        BigDecimal budgetLimit = tripMemberUpdateRequest.getBudgetLimit();
        String travelPace = tripMemberUpdateRequest.getTravelPace();
        String favoriteActivities = tripMemberUpdateRequest.getFavoriteActivities();
        boolean updateResult = tripMemberService.update(tripId, userId, budgetLimit, travelPace, favoriteActivities, loginUser);
        return ResultUtils.success(updateResult);
    }

    /**
     * 添加旅行成员
     * @param tripMemberAddRequest 添加旅行成员请求
     * @param request
     * @return
     */
    @PostMapping("add")
    public BaseResponse<Long> add(@RequestBody TripMemberAddRequest tripMemberAddRequest, HttpServletRequest request){
        if(tripMemberAddRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        long tripId = tripMemberAddRequest.getTripId();
        String role = TripMemberRoleEnum.MEMBER.getValue();
        BigDecimal budgetLimit = tripMemberAddRequest.getBudgetLimit();
        String inviteCode = tripMemberAddRequest.getInviteCode();
        long tripMemberId = tripMemberService.add(tripId, role, inviteCode, loginUser, budgetLimit);
        return ResultUtils.success(tripMemberId);
    }
}
