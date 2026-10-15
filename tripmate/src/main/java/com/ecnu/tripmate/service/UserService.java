package com.ecnu.tripmate.service;

import com.ecnu.tripmate.model.dto.user.UserUpdateRequest;
import com.ecnu.tripmate.model.vo.user.LoginUserVO;
import com.mybatisflex.core.service.IService;
import com.ecnu.tripmate.model.entity.User;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户表 服务层。
 *
 * @author Wolfcub
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册
     * @param userAccount 账号
     * @param userPassword 密码
     * @param checkPassword 二次密码
     * @return id
     */
    Long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 密码加密
     * @param userPassword 原始密码
     * @return 加密后的密码
     */
    String getEncryptPassword(String userPassword);

    /**
     * 获取脱敏后的用户信息
     * @param user 用户完整信息
     * @return 用户脱敏信息
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 用户登录
     * @param userAccount 用户账号
     * @param userPassword 用户密码
     * @param request
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 用户信息更新
     * @param userUpdateRequest 用户信息请求
     * @return
     */
    boolean userUpdate(UserUpdateRequest userUpdateRequest, User loginUser, HttpServletRequest request);

    /**
     * 获取当前登录用户
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);
}
