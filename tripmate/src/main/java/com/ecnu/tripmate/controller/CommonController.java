package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.ratelimiter.annotation.RateLimit;
import com.ecnu.tripmate.ratelimiter.enums.RateLimitType;
import com.ecnu.tripmate.service.ImageUploadService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用接口控制器。
 * 负责处理图片上传等通用请求。
 */
@RestController
@RequestMapping("/common")
public class CommonController {

    @Resource
    private ImageUploadService imageUploadService;

    /**
     * 处理并上传头像到 COS，保留现有上传路径。
     *
     * @param file 前端上传的图片文件
     * @return 图片访问地址
     */
    @PostMapping("/upload")
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 86400, message = "今日头像修改次数已达上限（5次），请明天再试")
    public BaseResponse<String> upload(@RequestParam("file") MultipartFile file) {
        return ResultUtils.success(imageUploadService.uploadAvatar(file));
    }
}
