package com.ecnu.tripmate.service;

import com.ecnu.tripmate.config.AvatarImageProperties;
import com.ecnu.tripmate.exception.BusinessException;
import com.ecnu.tripmate.exception.ErrorCode;
import com.ecnu.tripmate.manage.AvatarImageProcessor;
import com.ecnu.tripmate.manage.CosManager;
import com.ecnu.tripmate.service.impl.ImageUploadServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageUploadServiceTest {
    private final CosManager cosManager = mock(CosManager.class);
    private final ImageUploadServiceImpl service = new ImageUploadServiceImpl();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "cosManager", cosManager);
        ReflectionTestUtils.setField(service, "avatarImageProcessor", new AvatarImageProcessor(new AvatarImageProperties()));
    }

    @Test
    void uploadsProcessedJpegAndDeletesTemporaryFile() throws Exception {
        AtomicReference<File> uploaded = new AtomicReference<>();
        when(cosManager.uploadFile(anyString(), any(File.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            File file = invocation.getArgument(1);
            uploaded.set(file);
            assertTrue(key.matches("avatars/\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}\\.jpg"));
            assertTrue(file.length() <= 100 * 1024);
            BufferedImage image = ImageIO.read(file);
            assertEquals(256, image.getWidth());
            assertEquals(256, image.getHeight());
            return "https://example.test/" + key;
        });
        assertTrue(service.uploadAvatar(png()).startsWith("https://example.test/avatars/"));
        assertFalse(uploaded.get().exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception", "null", "blank"})
    void deletesTemporaryFileWhenStorageFails(String failure) throws Exception {
        AtomicReference<File> uploaded = new AtomicReference<>();
        when(cosManager.uploadFile(anyString(), any(File.class))).thenAnswer(invocation -> {
            uploaded.set(invocation.getArgument(1));
            if (failure.equals("exception")) throw new IllegalStateException("storage unavailable");
            return failure.equals("null") ? null : " ";
        });
        MockMultipartFile file = png();
        BusinessException error = assertThrows(BusinessException.class, () -> service.uploadAvatar(file));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), error.getCode());
        assertFalse(uploaded.get().exists());
    }

    @Test
    void rejectsInvalidImagesWithoutUploading() {
        assertThrows(BusinessException.class, () -> service.uploadAvatar(
                new MockMultipartFile("file", "fake.png", "image/png", new byte[]{1, 2, 3})));
        verifyNoInteractions(cosManager);
    }

    private static MockMultipartFile png() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", "avatar.png", "image/png", bytes.toByteArray());
    }
}
