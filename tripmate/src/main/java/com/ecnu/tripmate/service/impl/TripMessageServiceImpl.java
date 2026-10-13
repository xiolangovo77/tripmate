package com.ecnu.tripmate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.ecnu.tripmate.ai.AiItineraryGeneratorService;
import com.ecnu.tripmate.ai.model.MessageResponseResult;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.trip.TripMemberRoleEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageChangeStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageRecallStatusEnum;
import com.ecnu.tripmate.model.enums.tripMessage.TripMessageRoleEnum;
import com.ecnu.tripmate.model.vo.tripMessage.TripMessageVO;
import com.ecnu.tripmate.service.TripMemberService;
import com.ecnu.tripmate.service.TripService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.TripMessage;
import com.ecnu.tripmate.mapper.TripMessageMapper;
import com.ecnu.tripmate.service.TripMessageService;
import dev.langchain4j.agent.tool.P;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 旅行消息表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class TripMessageServiceImpl extends ServiceImpl<TripMessageMapper, TripMessage>  implements TripMessageService{

    @Resource
    private TripService tripService;

    @Resource
    private TripMemberService tripMemberService;

    @Resource
    private AiItineraryGeneratorService aiItineraryGeneratorService;

    @Override
    public long add(Long tripId, String content, Long replyToId, User loginUser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(StrUtil.hasBlank(content)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息不能为空");
        }
        if(replyToId != null && replyToId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "回复id不合法");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、判断该用户是否是该旅行的成员
        Long userId = loginUser.getId();
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", loginUser.getId());
        long count = tripMemberService.getMapper().selectCountByQuery(queryWrapper);
        if(count <= 0){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有该旅行的成员才能发送消息");
        }
        // 4、判断回复的消息是否存在
        if(replyToId != null){
            TripMessage replyMessage = this.getById(replyToId);
            if(replyMessage == null){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "回复的消息不存在");
            }
        }
        // 5、设置默认值
        String role = TripMessageRoleEnum.USER.getValue();
        String changeStatus = TripMessageChangeStatusEnum.NONE.getValue();
        String recallStatus = TripMessageRecallStatusEnum.NORMAL.getValue();
        // 6、操作数据库
        TripMessage tripMessage = TripMessage.builder()
                .tripId(tripId)
                .senderId(userId)
                .role(role)
                .content(content)
                .replyToId(replyToId)
                .recallStatus(recallStatus)
                .changeStatus(changeStatus)
                .build();
        boolean saveResult = this.save(tripMessage);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "消息添加失败，数据库错误");
        }
        return tripMessage.getId();
    }

    @Override
    public String get(Long id) {
        // 1、参数校验
        if(id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id不合法");
        }
        // 2、数据库查询
        TripMessage tripMessage = this.getById(id);
        if(tripMessage == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息不存在");
        }
        // 3、判断消息是否撤回
        String recallStatus = tripMessage.getRecallStatus();
        // 如果撤回
        if(TripMessageRecallStatusEnum.RECALLED.getValue().equals(recallStatus)){
            return "消息已撤回";
        }
        return tripMessage.getContent();
    }

    @Override
    public List<TripMessageVO> page(Long tripId, int pageSize, LocalDateTime lastCreateTime, User loginUser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(!(pageSize >= 0 && pageSize <= 50)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "页面大小不合法");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、判断该用户是否是该旅行的成员
        Long userId = loginUser.getId();
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", loginUser.getId());
        long count = tripMemberService.getMapper().selectCountByQuery(queryWrapper);
        if(count <= 0){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有该旅行的成员才能查询消息");
        }
        // 4、封装分页sql请求
        QueryWrapper queryWrapper1 = new QueryWrapper();
        queryWrapper1.eq("tripId", tripId);
        // 游标查询逻辑 - 只是用lastCreateTime作为游标
        if(lastCreateTime != null){
            queryWrapper1.lt("createTime", lastCreateTime);
        }else{
            // 默认按照创建时间降序
            queryWrapper1.orderBy("createTime", false);
        }
        // 5、查询数据库
        Page<TripMessage> page = this.page(Page.of(1, pageSize), queryWrapper1);
        // 6、返回VO
        List<TripMessage> records = page.getRecords();
        List<TripMessageVO> tripMessageVOList = BeanUtil.copyToList(records, TripMessageVO.class);
        return tripMessageVOList;
    }

    @Override
    public String addAi(Long tripId, Long replyToId, User loginUser) {
        // 1、校验参数
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "旅行id不合法");
        }
        if(replyToId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息id不合法");
        }
        // 2、判断旅行是否存在
        tripService.isTripExists(tripId);
        // 3、判断该用户是否是该旅行的成员
        Long userId = loginUser.getId();
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("userId", userId);
        long count = tripMemberService.getMapper().selectCountByQuery(queryWrapper);
        if(count <= 0){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有该旅行的成员才能查询消息");
        }
        // 4、判断用户消息是否存在
        TripMessage tripMessage = this.getById(replyToId);
        if(tripMessage == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息不存在");
        }
        // 5、消息是否撤回
        String recallStatus = tripMessage.getRecallStatus();
        if(TripMessageRecallStatusEnum.RECALLED.getValue().equals(recallStatus)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息已撤回");
        }
        // 6、登录用户是否为发消息本人
        long senderId = tripMessage.getSenderId();
        if(senderId != userId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 7、调用ai
        String userMessage = tripMessage.getContent();
        MessageResponseResult messageResponseResult = aiItineraryGeneratorService.messageResponse(userMessage);
        if(messageResponseResult == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        // 8、ai返回结果校验
        String content = messageResponseResult.getContent();
        String changeStatus = messageResponseResult.getChangeStatus();
        if(StrUtil.hasBlank(content, changeStatus)){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        if(TripMessageChangeStatusEnum.getEnumByValue(changeStatus) == null){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI调用失败，请重试");
        }
        // 9、添加ai消息
        String role = TripMessageRoleEnum.ASSISTANT.getValue();
        String recallStatusAi = TripMessageRecallStatusEnum.NORMAL.getValue();
        TripMessage tripAiMessage = TripMessage.builder()
                .tripId(tripId)
                .role(role)
                .content(content)
                .replyToId(replyToId)
                .recallStatus(recallStatusAi)
                .changeStatus(changeStatus)
                .build();
        boolean saveResult = this.save(tripAiMessage);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存ai消息失败，数据库错误");
        }
        return content;
    }
}
