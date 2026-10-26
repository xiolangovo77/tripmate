package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.trip.TripAddRequest;
import com.ecnu.tripmate.model.dto.trip.TripPageRequest;
import com.ecnu.tripmate.model.dto.trip.TripUpdateRequest;
import com.ecnu.tripmate.model.dto.trip.TripUpdateStatusRequest;
import com.ecnu.tripmate.model.dto.user.UserRegisterRequest;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.trip.TripPageVO;
import com.ecnu.tripmate.model.vo.trip.TripVO;
import com.ecnu.tripmate.ratelimiter.annotation.RateLimit;
import com.ecnu.tripmate.ratelimiter.enums.RateLimitType;
import com.ecnu.tripmate.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.service.TripService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * 旅行表 控制层。
 *
 * @author Wolfcub
 */
@RestController
@RequestMapping("/trip")
public class TripController {

    @Resource
    private TripService tripService;

    @Resource
    private UserService userService;

    /**
     * 旅行添加
     * @param tripAddRequest 旅行添加参数
     * @param request
     * @return 旅行id
     */
    @PostMapping("add")
    @RateLimit(limitType = RateLimitType.USER, rate = 10, rateInterval = 2592000, rateLimiterTtl = 720, message = "30天内添加旅行次数已达上限（10次），请稍后再试")
    public BaseResponse<Long> add(@RequestBody TripAddRequest tripAddRequest, HttpServletRequest request){
        if(tripAddRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        Long tripId = tripService.addTrip(tripAddRequest, loginUser);
        return ResultUtils.success(tripId);
    }

    /**
     * 更新旅行状态
     * @param tripUpdateStatusRequest 更新旅行状态请求
     * @param request
     * @return
     */
    @PostMapping("updateStatus")
    public BaseResponse<Boolean> updateStatus(@RequestBody TripUpdateStatusRequest tripUpdateStatusRequest, HttpServletRequest request){
        if(tripUpdateStatusRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        long tripId = tripUpdateStatusRequest.getId();
        String status = tripUpdateStatusRequest.getStatus();
        boolean updateResult = tripService.updateTripStatus(tripId, status, loginUser);
        return ResultUtils.success(updateResult);
    }

    /**
     * 更新旅行信息
     * @param tripUpdateRequest 旅行信息请求
     * @param request
     * @return
     */
    @PostMapping("update")
    @RateLimit(limitType = RateLimitType.USER, rate = 30, rateInterval = 2592000, rateLimiterTtl = 720, message = "30天内更新旅行次数已达上限（30次），请稍后再试")
    public BaseResponse<Boolean> update(@RequestBody TripUpdateRequest tripUpdateRequest, HttpServletRequest request){
        if(tripUpdateRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        boolean updateResult = tripService.update(tripUpdateRequest, loginUser);
        return ResultUtils.success(updateResult);
    }

    /**
     * 获取旅行和人员信息
     * @param tripId 旅行id
     * @return
     */
    @GetMapping("get")
    public BaseResponse<TripVO> update(@RequestParam long tripId){
        TripVO result = tripService.getTripById(tripId);
        return ResultUtils.success(result);
    }

    /**
     * 获取行程初始化的提示词
     * @param tripId 旅行id
     * @return
     */
    @GetMapping("getPrompt")
    public BaseResponse<String> getPrompt(@RequestParam long tripId){
        String initItineraryPrompt = tripService.getInitItineraryPrompt(tripId);
        return ResultUtils.success(initItineraryPrompt);
    }

    /**
     * 分页获取旅行信息
     * @param tripPageRequest 分页获取旅行信息请求
     * @param request
     * @return
     */
    @PostMapping("page")
    public BaseResponse<List<TripPageVO>> page(@RequestBody TripPageRequest tripPageRequest, HttpServletRequest request){
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        List<TripPageVO> tripPageVOList = tripService.page(tripPageRequest, loginUser);
        return ResultUtils.success(tripPageVOList);
    }
}
