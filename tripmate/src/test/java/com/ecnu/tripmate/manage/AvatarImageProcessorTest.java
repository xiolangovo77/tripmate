package com.ecnu.tripmate.manage;

import com.ecnu.tripmate.config.AvatarImageProperties;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarImageProcessorTest {
    private final AvatarImageProperties properties = new AvatarImageProperties();
    private final AvatarImageProcessor processor = new AvatarImageProcessor(properties);

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "jpeg", "png", "bmp", "gif"})
    void convertsSupportedImagesToSmallSquareJpeg(String format) throws Exception {
        BufferedImage source = solid(800, 400, Color.RED);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(200, 0, 400, 400);
        graphics.dispose();
        byte[] jpeg = processor.process(upload(format, bytes(source, format)));
        BufferedImage result = read(jpeg);
        assertEquals(256, result.getWidth());
        assertEquals(256, result.getHeight());
        assertTrue(jpeg.length <= 100 * 1024);
        assertEquals(0xff, jpeg[0] & 0xff);
        assertEquals(0xd8, jpeg[1] & 0xff);
        assertColor(Color.BLUE, result, 10, 128);
        assertColor(Color.BLUE, result, 245, 128);
    }

    @Test
    void fillsTransparencyWithWhiteAndDoesNotUpscale() throws Exception {
        BufferedImage source = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        BufferedImage result = read(processor.process(upload("png", bytes(source, "png"))));
        assertEquals(40, result.getWidth());
        assertEquals(40, result.getHeight());
        assertColor(Color.WHITE, result, 20, 20);
    }

    @Test
    void appliesJpegExifOrientation() throws Exception {
        BufferedImage source = solid(80, 80, Color.RED);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 40, 80, 40);
        graphics.dispose();
        byte[] original = bytes(source, "jpeg");
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(tagged);
        data.write(original, 0, 2);
        // APP1: Exif + big-endian TIFF IFD with orientation 6 (90 degrees clockwise).
        data.writeShort(0xffe1);
        data.writeShort(34);
        data.write(new byte[]{'E', 'x', 'i', 'f', 0, 0, 'M', 'M'});
        data.writeShort(42);
        data.writeInt(8);
        data.writeShort(1);
        data.writeShort(0x0112);
        data.writeShort(3);
        data.writeInt(1);
        data.writeShort(6);
        data.writeShort(0);
        data.writeInt(0);
        data.write(original, 2, original.length - 2);
        BufferedImage result = read(processor.process(upload("jpg", tagged.toByteArray())));
        assertColor(Color.BLUE, result, 10, 10);
        assertColor(Color.RED, result, 70, 10);
    }

    @Test
    void readsStaticWebp() throws Exception {
        // Generated 16x16 solid-color WebP; no external service required by this test.
        byte[] webp = Base64.getDecoder().decode(
                "UklGRjoAAABXRUJQVlA4IC4AAAAQAgCdASoQABAAAUAmJaACdLoB+AH4AAPIAP7fgD/1BaGvl+NF/7WgODVf6NwA");
        BufferedImage result = read(processor.process(upload("webp", webp)));
        assertEquals(16, result.getWidth());
        assertColor(new Color(20, 150, 220), result, 8, 8);
    }

    @Test
    void readsFirstFrameOfAnimatedWebp() throws Exception {
        byte[] webp = Base64.getDecoder().decode(
                "UklGRsQAAABXRUJQVlA4WAoAAAACAAAADwAADwAAQU5JTQYAAAAAAAAAAABBTk1GSgAAAAAAAAAAAA8AAA8AAGQAAAJWUDggMgAAADABAJ0BKhAAEAABQCYloAADcAD+8ut///mwP/bz/wR6Af//0uD//pcH//S4P/SkAAAAQU5NRkYAAAAAAAAAAAAPAAAPAABkAAAAVlA4IC4AAAA0AQCdASoQABAAAAAmJaAAA3AA/vtV4///S4P/+lwf/9Lg/9Lg//rV5Vesq6AA");
        BufferedImage result = read(processor.process(upload("webp", webp)));
        assertColor(Color.RED, result, 8, 8);
    }

    @Test
    void readsOnlyFirstGifFrame() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(solid(30, 30, Color.RED), null, null), null);
            writer.writeToSequence(new IIOImage(solid(30, 30, Color.BLUE), null, null), null);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        BufferedImage result = read(processor.process(upload("gif", bytes.toByteArray())));
        assertColor(Color.RED, result, 15, 15);
    }

    @Test
    void enforcesOutputSizeForNoisyImages() throws Exception {
        BufferedImage source = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                source.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        byte[] png = bytes(source, "png");
        byte[] normal = processor.process(upload("png", png));
        properties.setMaxOutputBytes(35 * 1024);
        byte[] reduced = processor.process(upload("png", png));
        assertTrue(reduced.length <= properties.getMaxOutputBytes());
        assertTrue(reduced.length < normal.length);
        properties.setMaxOutputBytes(10);
        BusinessException error = assertThrows(BusinessException.class,
                () -> processor.process(upload("png", png)));
        assertTrue(error.getMessage().contains("压缩后"));
    }

    @Test
    void rejectsExcessiveDimensionsBeforeDecodingPixels() throws Exception {
        byte[] png = bytes(solid(1, 1, Color.RED), "png");
        assertDimensionRejected(png, 6001, 1);
        assertDimensionRejected(png, 5000, 4000);
    }

    @Test
    void rejectsEmptyOversizedAndMismatchedUploads() throws Exception {
        assertThrows(BusinessException.class, () -> processor.process(null));
        assertThrows(BusinessException.class, () -> processor.process(upload("png", new byte[0])));
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.getSize()).thenReturn(5L * 1024 * 1024 + 1);
        assertThrows(BusinessException.class, () -> processor.process(oversized));
        verify(oversized, never()).getInputStream();
        byte[] png = bytes(solid(16, 16, Color.BLUE), "png");
        assertThrows(BusinessException.class, () -> processor.process(upload("jpg", png)));
        assertThrows(BusinessException.class, () -> processor.process(
                new MockMultipartFile("file", "image.png", "text/plain", png)));
        assertThrows(BusinessException.class, () -> processor.process(upload("svg", png)));
        assertThrows(BusinessException.class, () -> processor.process(upload("png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})));
    }

    @Test
    void boundsActualReadEvenWhenDeclaredSizeIsWrong() throws Exception {
        properties.setMaxInputBytes(100);
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", new byte[101]) {
            @Override
            public long getSize() { return 1; }
        };
        assertThrows(BusinessException.class, () -> processor.process(file));
    }

    @Test
    void limitsConcurrentProcessingAndReleasesSlot() throws Exception {
        properties.setMaxConcurrent(1);
        AvatarImageProcessor limited = new AvatarImageProcessor(properties);
        byte[] png = bytes(solid(16, 16, Color.BLUE), "png");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MultipartFile blocking = mock(MultipartFile.class);
        when(blocking.getSize()).thenReturn((long) png.length);
        when(blocking.getOriginalFilename()).thenReturn("avatar.png");
        when(blocking.getContentType()).thenReturn("image/png");
        when(blocking.getInputStream()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return new ByteArrayInputStream(png);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> limited.process(blocking));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                BusinessException busy = assertThrows(BusinessException.class,
                        () -> limited.process(upload("png", png)));
                assertEquals(ErrorCode.TOO_MANY_REQUEST.getCode(), busy.getCode());
            } finally {
                release.countDown();
            }
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        }
        assertThrows(BusinessException.class, () -> limited.process(upload("png", new byte[]{1})));
        assertNotNull(limited.process(upload("png", png)));
    }

    private void assertDimensionRejected(byte[] source, int width, int height) {
        byte[] png = source.clone();
        ByteBuffer.wrap(png).putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        BusinessException error = assertThrows(BusinessException.class,
                () -> processor.process(upload("png", png)));
        assertTrue(error.getMessage().contains("图片尺寸过大"));
    }

    private static MockMultipartFile upload(String format, byte[] bytes) {
        return new MockMultipartFile("file", "avatar." + format, "image/" + format, bytes);
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static byte[] bytes(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes));
        return bytes.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static void assertColor(Color expected, BufferedImage image, int x, int y) {
        Color actual = new Color(image.getRGB(x, y));
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) < 25, actual.toString());
        assertTrue(Math.abs(expected.getGreen() - actual.getGreen()) < 25, actual.toString());
        assertTrue(Math.abs(expected.getBlue() - actual.getBlue()) < 25, actual.toString());
    }
}
