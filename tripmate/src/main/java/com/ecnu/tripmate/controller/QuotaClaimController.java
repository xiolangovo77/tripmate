package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.service.QuotaClaimService;
import com.ecnu.tripmate.quota.model.QuotaClaimResult;
import com.ecnu.tripmate.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/quota")
public class QuotaClaimController {
    /** 从会话取得领取人身份。 */
    private final UserService userService;
    /** 受理领取请求并查询本人的领取结果。 */
    private final QuotaClaimService quotaClaimService;

    @PostMapping("/activities/{id}/claims")
    public BaseResponse<QuotaClaimResult> claim(@PathVariable long id, HttpServletRequest request) {
        return ResultUtils.success(quotaClaimService.claim(id, userService.getLoginUser(request).getId()));
    }

    @GetMapping("/activities/{id}/claims/me")
    public BaseResponse<QuotaClaimResult> mine(@PathVariable long id, HttpServletRequest request) {
        return ResultUtils.success(quotaClaimService.find(id, userService.getLoginUser(request).getId()));
    }
}
