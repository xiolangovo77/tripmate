package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.Itinerary.ItineraryAddRequest;
import com.ecnu.tripmate.model.dto.Itinerary.ItineraryUpdateRequest;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.itinerary.ItineraryVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.ecnu.tripmate.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.ecnu.tripmate.service.ItineraryService;

import java.util.List;

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

    /**
     * 确认行程
     * @param id 行程id
     * @param request
     * @return
     */
    @PutMapping("confirmed")
    public BaseResponse<Integer> confirmed(@RequestParam Long id, HttpServletRequest request){
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        int confirmedResult = itineraryService.confirmed(id, loginUser);
        return ResultUtils.success(confirmedResult);
    }

    /**
     * 获取行程和行程明细
     * @param id 行程id
     * @return
     */
    @GetMapping("get")
    public BaseResponse<ItineraryVO> get(@RequestParam Long id){
        // 获取传入参数
        ItineraryVO ItineraryVO = itineraryService.get(id);
        return ResultUtils.success(ItineraryVO);
    }

    /**
     * 更新行程和行程明细
     * @param itineraryUpdateRequest 更新行程和行程明细请求
     * @param request
     * @return
     */
    @PutMapping("update")
    public BaseResponse<Long> update(@RequestBody ItineraryUpdateRequest itineraryUpdateRequest, HttpServletRequest request){
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        Long id = itineraryUpdateRequest.getId();
        List<TripMessageAiVO> messageList = itineraryUpdateRequest.getMessageList();
        Long updateResult = itineraryService.update(id, messageList, loginUser);
        return ResultUtils.success(updateResult);
    }

    /**
     * 根据旅行id获取行程id
     * @param tripId 旅行id
     * @return
     */
    @GetMapping("getIdByTripId")
    public BaseResponse<Long> getIdByTripId(@RequestParam Long tripId){
        // 获取传入参数
        Long id = itineraryService.getIdByTripId(tripId);
        return ResultUtils.success(id);
    }
}
