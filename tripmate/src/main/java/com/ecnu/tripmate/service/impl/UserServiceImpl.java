package com.ecnu.tripmate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.ecnu.tripmate.constant.UserConstant;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.model.dto.user.UserUpdateRequest;
import com.ecnu.tripmate.model.enums.TravelPaceEnum;
import com.ecnu.tripmate.model.enums.UserRoleEnum;
import com.ecnu.tripmate.model.vo.user.LoginUserVO;
import com.ecnu.tripmate.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ecnu.tripmate.model.entity.User;
import com.ecnu.tripmate.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static com.ecnu.tripmate.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户表 服务层实现。
 *
 * @author Wolfcub
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>  implements UserService {


    @Override
    public Long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1、参数校验
        if(StrUtil.hasBlank(userAccount, userPassword, checkPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if(userAccount.length() < 4){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if(userPassword.length() < 6 || checkPassword.length() < 6){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if(!userPassword.equals(checkPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        // 2、检查是否重复
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount);
        long count = this.mapper.selectCountByQuery(queryWrapper);
        if(count > 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
        // 3、加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 4、设置默认值
        String defaultNickName = "无名";
        String defaultTravelPace = TravelPaceEnum.BALANCED.getValue();
        String defaultFavoriteActivities = JSONUtil.toJsonStr(List.of("美食", "自然", "拍照"));
        String defaultUserRole = UserRoleEnum.USER.getValue();
        // 5、插入数据
        User user = User.builder()
                .userAccount(userAccount)
                .password(encryptPassword)
                .nickname(defaultNickName)
                .userRole(defaultUserRole)
                .travelPace(defaultTravelPace)
                .favoriteActivities(defaultFavoriteActivities)
                .build();
        boolean saveResult = this.save(user);
        if(!saveResult){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
        }
        return user.getId();
    }

    @Override
    public String getEncryptPassword(String userPassword) {
        final String SALT = "Wolf cub";
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if(user == null){
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1、参数校验
        if(StrUtil.hasBlank(userAccount, userPassword) || request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if(userAccount.length() < 4){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        if(userPassword.length() < 6){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 2、加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 3、查询用户
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount)
                .eq("password", encryptPassword);
        User user = this.mapper.selectOneByQuery(queryWrapper);
        // 用户不存在
        if(user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 4、保存用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        // 5、获得脱敏后的用户信息
        return this.getLoginUserVO(user);
    }

    @Override
    public boolean userUpdate(UserUpdateRequest userUpdateRequest, User loginUser, HttpServletRequest request) {
        // 1、获取参数
        String userAccount = userUpdateRequest.getUserAccount();
        String nickname = userUpdateRequest.getNickname();
        String avatarUrl = userUpdateRequest.getAvatarUrl();
        String travelPace = userUpdateRequest.getTravelPace();
        String favoriteActivities = userUpdateRequest.getFavoriteActivities();
        // 2、检验参数
        if(StrUtil.hasBlank(userAccount, nickname, travelPace, favoriteActivities)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if(userAccount.length() < 4){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号错误");
        }
        if(TravelPaceEnum.getEnumByValue(travelPace) == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请选择正确的旅行节奏");
        }
        try {
            JSONArray jsonArray = JSONUtil.parseArray(favoriteActivities);
            if (jsonArray.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "活动偏好不能为空");
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "活动偏好格式错误");
        }
        // 3、权限校验，只有本人可以修改
        String loginUserAccount = loginUser.getUserAccount();
        if(!loginUserAccount.equals(userAccount)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 4、查询用户是否存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount);
        User user = this.mapper.selectOneByQuery(queryWrapper);
        // 用户不存在
        if(user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在");
        }
        // 5、设置用户信息
        user.setNickname(nickname);
        user.setAvatarUrl(avatarUrl);
        user.setFavoriteActivities(favoriteActivities);
        user.setTravelPace(travelPace);
        user.setUpdateTime(LocalDateTime.now());
        // 6、更新用户信息
        boolean result = this.updateById(user);
        if(!result){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "数据库错误，更新失败");
        }
        // 7、更新session
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        return result;
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否登录
        Object userObject = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User)userObject;
        if(currentUser == null){
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        // 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }
}
