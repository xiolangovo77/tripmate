package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.constant.UserConstant;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.quota.QuotaActivityService;
import com.ecnu.tripmate.quota.model.ActivityDetail;
import com.ecnu.tripmate.quota.model.CreateActivityRequest;
import com.ecnu.tripmate.quota.model.UpdateActivityDisplayRequest;
import com.ecnu.tripmate.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/quota/activities")
@RequiredArgsConstructor
public class QuotaActivityController {
    /**
     * 处理活动详情查询、发布、配置更新和库存恢复。
     */
    private final QuotaActivityService quotaActivityService;

    /**
     * 查询当前登录用户及其管理员权限。
     */
    private final UserService userService;

    @GetMapping("/{id}")
    public BaseResponse<ActivityDetail> detail(@PathVariable long id) {
        return ResultUtils.success(quotaActivityService.detail(id));
    }

    @PostMapping
    public BaseResponse<Long> create(@RequestBody CreateActivityRequest draft, HttpServletRequest request) {
        requireAdmin(request);
        return ResultUtils.success(quotaActivityService.create(draft));
    }

    @PostMapping("/{id}/publish")
    public BaseResponse<Boolean> publish(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        quotaActivityService.publish(id);
        return ResultUtils.success(true);
    }

    @PutMapping("/{id}/config")
    public BaseResponse<Boolean> update(@PathVariable long id, @RequestBody UpdateActivityDisplayRequest display,
                                        HttpServletRequest request) {
        requireAdmin(request);
        quotaActivityService.updateDisplay(id, display);
        return ResultUtils.success(true);
    }

    @PostMapping("/{id}/stock/recover")
    public BaseResponse<Long> recoverStock(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        return ResultUtils.success(quotaActivityService.recoverStock(id));
    }

    private void requireAdmin(HttpServletRequest request) {
        var currentUser = userService.getById(userService.getLoginUser(request).getId());
        if (currentUser == null || !UserConstant.ADMIN_ROLE.equals(currentUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }
}
