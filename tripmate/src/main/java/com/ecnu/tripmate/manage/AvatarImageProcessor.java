package com.ecnu.tripmate.manage;

import com.ecnu.tripmate.config.AvatarImageProperties;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** 将上传图片转换为尺寸和体积受限的静态 JPEG 头像。 */
@Component
public class AvatarImageProcessor {
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private final AvatarImageProperties properties;
    private final Semaphore processingSlots;

    public AvatarImageProcessor(AvatarImageProperties properties) {
        Assert.isTrue(properties.getMaxInputBytes() > 0 && properties.getMaxInputBytes() < Integer.MAX_VALUE,
                "头像输入大小限制无效");
        Assert.isTrue(properties.getMaxDimension() > 0 && properties.getMaxPixels() > 0,
                "头像像素限制必须为正数");
        Assert.isTrue(properties.getOutputSize() > 0 && properties.getMaxOutputBytes() > 0,
                "头像输出限制必须为正数");
        Assert.isTrue(properties.getQuality() > 0 && properties.getQuality() <= 1,
                "头像质量必须在 (0, 1] 范围内");
        Assert.isTrue(properties.getMaxConcurrent() > 0, "头像处理并发数必须为正数");
        this.properties = properties;
        this.processingSlots = new Semaphore(properties.getMaxConcurrent());
    }

    public byte[] process(MultipartFile file) throws IOException {
        String extension = validateUpload(file);
        if (!processingSlots.tryAcquire()) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "头像处理中，请稍后重试");
        }
        try {
            BufferedImage thumbnail = decodeThumbnail(file, extension);
            try {
                return encodeJpeg(thumbnail);
            } finally {
                thumbnail.flush();
            }
        } finally {
            processingSlots.release();
        }
    }

    private String validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("上传文件不能为空");
        }
        if (file.getSize() > properties.getMaxInputBytes()) {
            throw invalid("头像原文件不能超过 " + properties.getMaxInputBytes() / (1024 * 1024) + " MB");
        }
        String filename = file.getOriginalFilename();
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        String contentType = file.getContentType();
        if (!EXTENSIONS.contains(extension) || contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw invalid("只允许上传有效的 jpg、jpeg、png、gif、webp、bmp 图片");
        }
        return extension.equals("jpg") ? "jpeg" : extension;
    }

    private BufferedImage decodeThumbnail(MultipartFile file, String extension) {
        try (InputStream input = file.getInputStream()) {
            // 限制实际读取量，避免仅信任 MultipartFile 声明的大小。
            byte[] bytes = input.readNBytes(properties.getMaxInputBytes() + 1);
            if (bytes.length > properties.getMaxInputBytes()) {
                throw invalid("头像原文件超出大小限制");
            }
            int side = inspectDimensions(bytes, extension);
            // 从输入流读取以保留 JPEG EXIF 方向信息；动图仅取第一帧。
            try (InputStream imageInput = new ByteArrayInputStream(bytes)) {
                return Thumbnails.of(imageInput)
                        .useExifOrientation(true)
                        // GIF 等索引色图片先转为真彩色，防止缩放时丢失原调色板。
                        .imageType(BufferedImage.TYPE_INT_ARGB)
                        .crop(Positions.CENTER)
                        .size(side, side)
                        .asBufferedImage();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid("图片无法解码或已损坏，请更换图片");
        }
    }

    private int inspectDimensions(byte[] bytes, String extension) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid("图片无法解码或格式不受支持");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if (!reader.getFormatName().equalsIgnoreCase(extension)) {
                    throw invalid("图片实际格式与文件扩展名不一致");
                }
                // 在完整解码之前检查；使用 long 防止宽高乘积溢出。
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || width > properties.getMaxDimension() || height > properties.getMaxDimension()
                        || (long) width * height > properties.getMaxPixels()) {
                    throw invalid("图片尺寸过大，单边不能超过 " + properties.getMaxDimension()
                            + " 像素，总像素不能超过 " + properties.getMaxPixels());
                }
                return Math.min(properties.getOutputSize(), Math.min(width, height));
            } finally {
                reader.dispose();
            }
        }
    }

    private byte[] encodeJpeg(BufferedImage thumbnail) throws IOException {
        BufferedImage rgb = new BufferedImage(thumbnail.getWidth(), thumbnail.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(thumbnail, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        try {
            // 质量是编码参数，不是压缩比例；每次编码后检查实际字节数。
            for (int attempt = 0; attempt < 5; attempt++) {
                float quality = Math.max(0.1f, properties.getQuality() - attempt * 0.1f);
                byte[] jpeg = writeJpeg(rgb, quality);
                if (jpeg.length <= properties.getMaxOutputBytes()) {
                    return jpeg;
                }
            }
            throw invalid("头像压缩后仍超出大小限制，请选择更简单的图片");
        } finally {
            rgb.flush();
        }
    }

    private byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG 编码器不可用");
        }
        ImageWriter writer = writers.next();
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            // 不复制原图元数据，避免携带拍摄位置等信息。
            writer.write(null, new IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAMS_ERROR, message);
    }
}
