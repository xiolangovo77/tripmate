package com.ecnu.tripmate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.entity.Trip;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.model.enums.TripInvitationStatusEnum;
import com.ecnu.tripmate.model.enums.trip.TripStatusEnum;
import com.ecnu.tripmate.model.vo.tripInvitation.TripInvitationVO;
import com.ecnu.tripmate.service.TripService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.TripInvitation;
import com.ecnu.tripmate.mapper.TripInvitationMapper;
import com.ecnu.tripmate.service.TripInvitationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 旅行邀请表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class TripInvitationServiceImpl extends ServiceImpl<TripInvitationMapper, TripInvitation>  implements TripInvitationService{

    @Resource
    private TripService tripService;

    // 邀请码固定为 8 位大写字母和数字，生成时排除容易混淆的 0、1、I、O。
    private static final Pattern INVITE_CODE_PATTERN = Pattern.compile("[A-HJ-NP-Z2-9]{8}");

    @Override
    public long add(long tripId, String inviteCode, User loginuser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "tripId不合法");
        }
        validateInviteCode(inviteCode);
        // 2、检验旅行是否存在
        Trip trip = tripService.isTripExists(tripId);
        // 3、检验是否有权限
        long ownerId = trip.getOwnerId();
        long loginUserId = loginuser.getId();
        if(loginUserId != ownerId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 4、判断是否存在该邀请码
        String activeStatus = TripInvitationStatusEnum.ACTIVE.getValue();
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("inviteCode", inviteCode)
                .eq("status", activeStatus);
        long count = this.mapper.selectCountByQuery(queryWrapper);
        if(count > 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码重复使用");
        }
        // 5、将改旅行的有效邀请码全部设置为无效
        QueryWrapper queryWrapper1 = new QueryWrapper();
        queryWrapper1.eq("tripId", tripId)
                .eq("status", activeStatus);
        this.mapper.updateByQuery(TripInvitation.builder()
                .status(TripInvitationStatusEnum.REVOKED.getValue())
                .build(), queryWrapper1);
        // 6、设置默认值
        LocalDateTime expiresTime = LocalDateTime.now().plusDays(7);
        // 7、操作数据库
        TripInvitation tripInvitation = TripInvitation.builder()
                .tripId(tripId)
                .inviterId(loginUserId)
                .inviteCode(inviteCode)
                .expiresTime(expiresTime)
                .status(activeStatus)
                .build();
        boolean saveResult = this.save(tripInvitation);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "添加邀请码失败，数据库错误");
        }
        return tripInvitation.getId();
    }

    @Override
    public boolean updateStatus(long tripId, String inviteCode, String status, User loginuser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "tripId不合法");
        }
        validateInviteCode(inviteCode);
        if(TripInvitationStatusEnum.getEnumByValue(status) == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码状态不合法");
        }
        // 2、旅行是否存在
        Trip trip = tripService.isTripExists(tripId);
        // 3、邀请码是否存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("inviteCode", inviteCode);
        long count = this.mapper.selectCountByQuery(queryWrapper);
        if(count <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码不存在");
        }
        // 4、是否有权限
        long ownerId = trip.getOwnerId();
        long loginUserId = loginuser.getId();
        if(loginUserId != ownerId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 5、修改邀请码状态
        QueryWrapper queryWrapper1 = new QueryWrapper();
        queryWrapper1.eq("tripId", tripId)
                .eq("inviteCode", inviteCode);
        boolean updateResult = this.update(TripInvitation.builder()
                .status(status)
                .build(), queryWrapper1);
        if(!updateResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新邀请码状态失败，数据库错误");
        }
        return updateResult;
    }

    @Override
    public TripInvitationVO get(long tripId, User loginuser) {
        // 1、参数校验
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "tripId不合法");
        }
        // 2、检验旅行是否存在
        Trip trip = tripService.isTripExists(tripId);
        // 3、获取改旅行的有效邀请码
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("status", TripInvitationStatusEnum.ACTIVE.getValue());
        List<TripInvitation> tripInvitationList = this.mapper.selectListByQuery(queryWrapper);
        // 没获取到邀请码
        if(tripInvitationList == null || tripInvitationList.isEmpty()){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该旅行没有邀请码");
        }
        // 4、返回结果
        TripInvitation tripInvitation = tripInvitationList.getFirst();
        TripInvitationVO tripInvitationVO = new TripInvitationVO();
        BeanUtil.copyProperties(tripInvitation, tripInvitationVO);
        return tripInvitationVO;
    }

    @Override
    public boolean checkInviteCode(long tripId, String inviteCode) {
        // 1、检验参数
        if(tripId <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "tripId不合法");
        }
        validateInviteCode(inviteCode);
        // 2、旅行是否存在
        Trip trip = tripService.isTripExists(tripId);
        // 3、旅行状态是否在筹备中
        if(!TripStatusEnum.PREPARING.getValue().equals(trip.getStatus())){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无法加入旅行");
        }
        // 4、邀请码是否存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("tripId", tripId)
                .eq("inviteCode", inviteCode);
        TripInvitation tripInvitation = this.mapper.selectOneByQuery(queryWrapper);
        if(tripInvitation == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码不存在");
        }
        // 5、邀请码是否过期
        LocalDateTime expiresTime = tripInvitation.getExpiresTime();
        LocalDateTime now = LocalDateTime.now();
        if(expiresTime.isBefore(now)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码已过期");
        }
        // 6、邀请码是否已撤销
        if(TripInvitationStatusEnum.REVOKED.getValue().equals(tripInvitation.getStatus())){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码已撤销");
        }
        return true;
    }

    /**
     * 校验邀请码格式
     * @param inviteCode 邀请码
     */
    private void validateInviteCode(String inviteCode){
        if (inviteCode == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请输入邀请码");
        }
        if (!INVITE_CODE_PATTERN.matcher(inviteCode).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码格式不正确");
        }
    }
}
