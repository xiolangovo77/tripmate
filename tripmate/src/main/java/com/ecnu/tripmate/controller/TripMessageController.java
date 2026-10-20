package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.tripMessage.TripMessageAddAiRequest;
import com.ecnu.tripmate.model.dto.tripMessage.TripMessageAddRequest;
import com.ecnu.tripmate.model.dto.tripMessage.TripMessageGetAiByChangeStatusRequest;
import com.ecnu.tripmate.model.dto.tripMessage.TripMessageUpdateChangeStatusByIdsRequest;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageAiVO;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageVO;
import com.ecnu.tripmate.service.TripMessageService;
import com.ecnu.tripmate.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 旅行消息表 控制层。
 *
 * @author Wolfcub
 */
@RestController
@RequestMapping("/tripMessage")
public class TripMessageController {

    @Resource
    private UserService userService;

    @Resource
    private TripMessageService tripMessageService;

    /**
     * 添加用户消息
     * @param tripMessageAddRequest 添加用户消息请求
     * @param request
     * @return
     */
    @PostMapping("add")
    public BaseResponse<Long> add(@RequestBody TripMessageAddRequest tripMessageAddRequest, HttpServletRequest request){
        if(tripMessageAddRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        Long tripId = tripMessageAddRequest.getTripId();
        String content = tripMessageAddRequest.getContent();
        Long replyToId = tripMessageAddRequest.getReplyToId();
        long tripMessageId = tripMessageService.add(tripId, content, replyToId, loginUser);
        return ResultUtils.success(tripMessageId);
    }

    /**
     * 根据id获取消息内容
     * @param id 消息id
     * @return
     */
    @PostMapping("get")
    public BaseResponse<String> get(@RequestParam Long id){
        String content = tripMessageService.get(id);
        return ResultUtils.success(content);
    }

    /**
     * 获取消息记录
     * @param tripId 旅行id
     * @param pageSize 页面大小
     * @param lastCreateTime 最后一条记录的创建时间
     * @param request
     * @return
     */
    @GetMapping("page")
    public BaseResponse<List<TripMessageVO>> page(@RequestParam Long tripId,
                                                  @RequestParam(defaultValue = "10") int pageSize,
                                                  @RequestParam(required = false) LocalDateTime lastCreateTime,
                                                  @RequestParam(required = false) Long lastId,
                                                  HttpServletRequest request
                                                  ){
        // 获取登录用户
        User loginUser = userService.getLoginUser(request);
        List<TripMessageVO> tripMessageVOList = tripMessageService.page(tripId, pageSize, lastCreateTime, lastId, loginUser);
        return ResultUtils.success(tripMessageVOList);
    }

    /**
     * 添加ai消息
     * @param tripMessageAddAiRequest 用户信息请求
     * @param request
     * @return
     */
    @PostMapping("addAi")
    public BaseResponse<String> addAi(@RequestBody TripMessageAddAiRequest tripMessageAddAiRequest, HttpServletRequest request){
        if(tripMessageAddAiRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        Long tripId = tripMessageAddAiRequest.getTripId();
        Long replyToId = tripMessageAddAiRequest.getReplyToId();
        String aiMessage = tripMessageService.addAi(tripId, replyToId, loginUser);
        return ResultUtils.success(aiMessage);
    }

    /**
     * 根据建议状态获取的ai消息列表
     * @param tripMessageGetAiByChangeStatusRequest 根据建议状态获取的ai消息列表请求
     * @param request
     * @return
     */
    @PostMapping("getAiByChangeStatus")
    public BaseResponse<List<TripMessageAiVO>> getAiByChangeStatus(@RequestBody TripMessageGetAiByChangeStatusRequest tripMessageGetAiByChangeStatusRequest, HttpServletRequest request){
        if(tripMessageGetAiByChangeStatusRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        Long tripId = tripMessageGetAiByChangeStatusRequest.getTripId();
        String changeStatus = tripMessageGetAiByChangeStatusRequest.getChangeStatus();
        List<TripMessageAiVO> tripMessageAiVOList = tripMessageService.getAiByChangeStatus(tripId, changeStatus, loginUser);
        return ResultUtils.success(tripMessageAiVOList);
    }

    /**
     * 批量修改ai消息的建议状态
     * @param tripMessageUpdateChangeStatusByIdsRequest 批量修改ai消息的建议状态请求
     * @param request
     * @return
     */
    @PostMapping("updateChangeStatusByIds")
    public BaseResponse<Integer> updateChangeStatusByIds(@RequestBody TripMessageUpdateChangeStatusByIdsRequest tripMessageUpdateChangeStatusByIdsRequest, HttpServletRequest request){
        if(tripMessageUpdateChangeStatusByIdsRequest == null || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取用户登录信息
        User loginUser = userService.getLoginUser(request);
        // 获取传入参数
        Long tripId = tripMessageUpdateChangeStatusByIdsRequest.getTripId();
        List<Long> ids = tripMessageUpdateChangeStatusByIdsRequest.getIds();
        String changeStatus = tripMessageUpdateChangeStatusByIdsRequest.getChangeStatus();
        int updateCount = tripMessageService.updateChangeStatusByIds(tripId, ids, changeStatus, loginUser);
        return ResultUtils.success(updateCount);
    }
}
