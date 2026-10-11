package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.Itinerary.ItineraryAddRequest;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.ecnu.tripmate.service.ItineraryService;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行程表 控制层。
 *
 * @author Wolfcub
 */
@RestController
@RequestMapping("/itinerary")
public class ItineraryController {

    @Resource
    private UserService userService;

    @Resource
    private ItineraryService itineraryService;

    /**
     * 添加行程
     * @param itineraryAddRequest 添加行程请求
     * @param request
     * @return
     */
    @PostMapping("add")
    public BaseResponse<Long> add(@RequestBody ItineraryAddRequest itineraryAddRequest, HttpServletRequest request){
        if(itineraryAddRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        Long tripId = itineraryAddRequest.getTripId();
        String initItineraryPrompt = itineraryAddRequest.getInitItineraryPrompt();
        Long itineraryId = itineraryService.add(tripId, initItineraryPrompt, loginUser);
        return ResultUtils.success(itineraryId);
    }
}
