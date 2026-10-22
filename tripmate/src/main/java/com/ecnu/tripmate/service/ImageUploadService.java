package com.ecnu.tripmate.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传服务。
 */
public interface ImageUploadService {

    /**
     * 校验、裁剪并压缩头像后上传到对象存储。
     *
     * @param file 上传的图片文件
     * @return 图片访问地址
     */
    String uploadAvatar(MultipartFile file);
}
