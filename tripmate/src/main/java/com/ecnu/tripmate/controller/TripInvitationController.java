package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.trip.TripAddRequest;
import com.ecnu.tripmate.model.dto.tripInvitation.TripInvitationAddRequest;
import com.ecnu.tripmate.model.dto.tripInvitation.TripInvitationCheckRequest;
import com.ecnu.tripmate.model.dto.tripInvitation.TripInvitationUpdateStatusRequest;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.tripInvitation.TripInvitationVO;
import com.ecnu.tripmate.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecnu.tripmate.model.entity.TripInvitation;
import com.ecnu.tripmate.service.TripInvitationService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * 旅行邀请表 控制层。
 *
 * @author Wolfcub
 */
@RestController
@RequestMapping("/tripInvitation")
public class TripInvitationController {

    @Resource
    private TripInvitationService tripInvitationService;

    @Resource
    private UserService userService;

    /**
     * 初始化邀请码
     * @param tripInvitationAddRequest 初始化邀请码请求
     * @param request
     * @return
     */
    @PostMapping("add")
    public BaseResponse<Long> add(@RequestBody TripInvitationAddRequest tripInvitationAddRequest, HttpServletRequest request){
        if(tripInvitationAddRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        long tripId = tripInvitationAddRequest.getTripId();
        String inviteCode = tripInvitationAddRequest.getInviteCode();
        long tripInvitationId = tripInvitationService.add(tripId, inviteCode, loginUser);
        return ResultUtils.success(tripInvitationId);
    }

    /**
     * 更新邀请码状态
     * @param tripInvitationUpdateStatusRequest 更新邀请码状态请求
     * @param request
     * @return
     */
    @PostMapping("updateStatus")
    public BaseResponse<Boolean> updateStatus(@RequestBody TripInvitationUpdateStatusRequest tripInvitationUpdateStatusRequest, HttpServletRequest request){
        if(tripInvitationUpdateStatusRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        long tripId = tripInvitationUpdateStatusRequest.getTripId();
        String inviteCode = tripInvitationUpdateStatusRequest.getInviteCode();
        String status = tripInvitationUpdateStatusRequest.getStatus();
        boolean updateStatusResult = tripInvitationService.updateStatus(tripId, inviteCode, status, loginUser);
        return ResultUtils.success(updateStatusResult);
    }

    /**
     * 获取旅行的邀请码
     * @param tripId 旅行id
     * @param request
     * @return
     */
    @GetMapping("getById")
    public BaseResponse<TripInvitationVO> getById(@RequestParam long tripId, HttpServletRequest request){
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        TripInvitationVO tripInvitationVO = tripInvitationService.get(tripId, loginUser);
        return ResultUtils.success(tripInvitationVO);
    }

    /**
     * 校验邀请码
     * @param tripInvitationCheckRequest 校验邀请码请求
     * @return
     */
    @PostMapping("check")
    public BaseResponse<Boolean> check(@RequestBody TripInvitationCheckRequest tripInvitationCheckRequest){
        if(tripInvitationCheckRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取传入参数
        long tripId = tripInvitationCheckRequest.getTripId();
        String inviteCode = tripInvitationCheckRequest.getInviteCode();
        boolean checkResult = tripInvitationService.checkInviteCode(tripId, inviteCode);
        return ResultUtils.success(checkResult);
    }
}
