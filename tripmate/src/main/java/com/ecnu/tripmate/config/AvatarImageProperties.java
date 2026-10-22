package com.ecnu.tripmate.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 头像处理限制；文件字节数与像素数分别限制。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "avatar.image")
public class AvatarImageProperties {
    /**
     * 上传原文件的最大字节数，默认 5 MB（5 × 1024 × 1024 字节）。
     */
    private int maxInputBytes = 5 * 1024 * 1024;

    /**
     * 原图宽度或高度的最大值，单位为像素，默认 6000；任一边超限即拒绝。
     */
    private int maxDimension = 6000;

    /**
     * 原图总像素数上限（宽 × 高），默认 1600 万，用于限制解码时的内存开销。
     */
    private long maxPixels = 16_000_000;

    /**
     * 裁剪后正方形头像的最大边长，单位为像素，默认 256；较小的图片不放大。
     */
    private int outputSize = 256;

    /**
     * JPEG 头像的最大输出字节数，默认 100 KB（100 × 1024 字节）。
     */
    private int maxOutputBytes = 100 * 1024;

    /**
     * JPEG 初始编码质量，取值范围为 (0, 1]，默认 0.85；超出体积限制时逐步降低。
     * 该值不是文件大小的压缩比例。
     */
    private float quality = 0.85f;

    /**
     * 单个应用实例同时处理头像的最大数量，默认 2；达到上限时提示稍后重试。
     */
    private int maxConcurrent = 2;
}
