package com.ecnu.tripmate.controller;

import com.ecnu.tripmate.common.BaseResponse;
import com.ecnu.tripmate.common.ResultUtils;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.manage.CosManager;
import com.ecnu.tripmate.ratelimiter.annotation.RateLimit;
import com.ecnu.tripmate.ratelimiter.enums.RateLimitType;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 通用接口控制器。
 * 负责处理图片上传等通用请求。
 */
@RestController
@RequestMapping("/common")
@Slf4j
public class CommonController {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Resource
    private CosManager cosManager;

    /**
     * 上传图片到 COS。
     *
     * @param file 前端上传的图片文件
     * @return 图片访问地址
     */
    @PostMapping("/upload")
    @RateLimit(limitType = RateLimitType.USER, rate = 500, rateInterval = 86400, message = "今日头像修改次数已达上限（5次），请明天再试")
    public BaseResponse<String> upload(@RequestParam("file") MultipartFile file) {
        // 1. 校验文件是否为空
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        }

        // 2. 校验文件扩展名、MIME 类型和文件头
        String extension = getExtension(file.getOriginalFilename());
        String contentType = file.getContentType();
        if (!IMAGE_EXTENSIONS.contains(extension)
                || contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                || !hasValidImageSignature(file, extension)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "只允许上传有效的 jpg、jpeg、png、gif、webp、bmp 图片");
        }

        // 3. 生成 COS 对象键：yyyy/MM/dd/UUID.后缀
        String datePath = LocalDate.now().format(DATE_FORMATTER);
        String key = datePath + "/" + UUID.randomUUID().toString().replace("-", "") + "." + extension;

        // 4. 兼容现有 CosManager：使用 File 参数上传，不修改 CosManager
        File tempFile = null;
        try {
            String suffix = "." + extension;
            tempFile = Files.createTempFile("tripmate-upload-", suffix).toFile();
            file.transferTo(tempFile);

            String url = cosManager.uploadFile(key, tempFile);
            if (url == null || url.isBlank()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败，请稍后重试");
            }
            return ResultUtils.success(url);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片上传 COS 失败，key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败，请稍后重试");
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("临时上传文件删除失败：{}", tempFile.getAbsolutePath());
            }
        }
    }

    /**
     * 根据文件头校验图片的实际格式，避免只依赖前端传入的文件名和 MIME 类型。
     *
     * @param file 上传文件
     * @param extension 文件扩展名
     * @return 文件头是否符合对应图片格式
     */
    private boolean hasValidImageSignature(MultipartFile file, String extension) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(12);
            return switch (extension) {
                case "jpg", "jpeg" -> header.length >= 3
                        && (header[0] & 0xFF) == 0xFF
                        && (header[1] & 0xFF) == 0xD8
                        && (header[2] & 0xFF) == 0xFF;
                case "png" -> header.length >= 8
                        && (header[0] & 0xFF) == 0x89
                        && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
                        && header[4] == 0x0D && header[5] == 0x0A
                        && header[6] == 0x1A && header[7] == 0x0A;
                case "gif" -> header.length >= 6
                        && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                        && header[3] == '8' && (header[4] == '7' || header[4] == '9')
                        && header[5] == 'a';
                case "bmp" -> header.length >= 2 && header[0] == 'B' && header[1] == 'M';
                case "webp" -> header.length >= 12
                        && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
                default -> false;
            };
        } catch (IOException e) {
            log.warn("读取上传文件头失败", e);
            return false;
        }
    }

    /**
     * 获取文件扩展名，并统一转换为小写。
     *
     * @param filename 原始文件名
     * @return 小写文件扩展名；无法获取时返回空字符串
     */
    private String getExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}