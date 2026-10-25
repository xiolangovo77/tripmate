package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.constant.UserConstant;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.exception.ThrowUtils;
import com.ecnu.tripmate.model.dto.user.UserLoginRequest;
import com.ecnu.tripmate.model.dto.user.UserRegisterRequest;
import com.ecnu.tripmate.model.dto.user.UserUpdateRequest;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.user.LoginUserVO;
import com.ecnu.tripmate.ratelimiter.annotation.RateLimit;
import com.ecnu.tripmate.ratelimiter.enums.RateLimitType;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.ecnu.tripmate.service.UserService;

/**
 * 用户表 控制层。
 *
 * @author Wolfcub
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户注册
     * @param userRegisterRequest 用户注册请求
     * @return 用户id
     */
    @PostMapping("register")
    @RateLimit(limitType = RateLimitType.API, rate = 30, rateInterval = 2592000, rateLimiterTtl = 720, message = "注册请求过于频繁，请稍后再试")
    public BaseResponse<Long> register(@RequestBody UserRegisterRequest userRegisterRequest){
        if(userRegisterRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        Long result = userService.userRegister(userAccount, userPassword, checkPassword);
        return ResultUtils.success(result);
    }

    /**
     * 用户登录
     * @param userLoginRequest 用户登录请求
     * @param request
     * @return 脱敏后的用户信息
     */
    @PostMapping("login")
    @RateLimit(limitType = RateLimitType.IP, rate = 100, rateInterval = 86400, rateLimiterTtl = 24, message = "登录请求过于频繁，请稍后再试")
    public BaseResponse<LoginUserVO> login(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request){
        if(userLoginRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        LoginUserVO result = userService.userLogin(userAccount, userPassword, request);
        return ResultUtils.success(result);
    }

    /**
     * 用户信息更新
     * @param userUpdateRequest 用户更新请求
     * @return
     */
    @PostMapping("update")
    public BaseResponse<Boolean> update(@RequestBody UserUpdateRequest userUpdateRequest,HttpServletRequest request){
        if(userUpdateRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        boolean result = userService.userUpdate(userUpdateRequest, loginUser, request);
        return ResultUtils.success(result);
    }

    /**
     * 用户退出
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }

    /**
     * 获取用户登录信息
     * @param request
     * @return
     */
    @GetMapping("get")
    public BaseResponse<LoginUserVO> get(HttpServletRequest request){
        User loginUser = userService.getLoginUser(request);
        LoginUserVO loginUserVO = userService.getLoginUserVO(loginUser);
        return ResultUtils.success(loginUserVO);
    }
}
