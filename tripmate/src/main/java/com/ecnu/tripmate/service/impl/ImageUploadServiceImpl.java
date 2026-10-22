package com.ecnu.tripmate.service.impl;

import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.manage.AvatarImageProcessor;
import com.ecnu.tripmate.manage.CosManager;
import com.ecnu.tripmate.service.ImageUploadService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 组织头像处理、COS 上传及临时文件清理。 */
@Service
@Slf4j
public class ImageUploadServiceImpl implements ImageUploadService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Resource
    private CosManager cosManager;

    @Resource
    private AvatarImageProcessor avatarImageProcessor;

    @Override
    public String uploadAvatar(MultipartFile file) {
        String key = "avatars/" + LocalDate.now().format(DATE_FORMATTER) + "/"
                + UUID.randomUUID().toString().replace("-", "") + ".jpg";
        File tempFile = null;
        try {
            byte[] jpeg = avatarImageProcessor.process(file);
            tempFile = Files.createTempFile("tripmate-avatar-", ".jpg").toFile();
            Files.write(tempFile.toPath(), jpeg);
            String url = cosManager.uploadFile(key, tempFile);
            if (url == null || url.isBlank()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败，请稍后重试");
            }
            return url;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("头像上传 COS 失败，key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败，请稍后重试");
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("临时上传文件删除失败：{}", tempFile.getAbsolutePath());
            }
        }
    }
}
