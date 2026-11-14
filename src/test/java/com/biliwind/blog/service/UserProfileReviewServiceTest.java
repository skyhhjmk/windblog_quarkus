package com.biliwind.blog.service;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserProfileReviewServiceTest {

    @Test
    void detectsSupportedImageMagicBytes() {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] gif = new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        byte[] invalid = new byte[]{'t', 'e', 'x', 't', '-', 'o', 'n', 'l', 'y', 0, 0, 0};

        assertEquals("image/jpeg", UserProfileReviewService.detectImageMime(jpeg));
        assertEquals("image/png", UserProfileReviewService.detectImageMime(png));
        assertEquals("image/gif", UserProfileReviewService.detectImageMime(gif));
        assertEquals("image/webp", UserProfileReviewService.detectImageMime(webp));
        assertNull(UserProfileReviewService.detectImageMime(invalid));
    }

    @Test
    void rejectsAvatarLargerThanTwoMib() {
        UserProfileReviewService service = new UserProfileReviewService();
        byte[] oversized = new byte[(int) UserProfileReviewService.MAX_AVATAR_BYTES + 1];
        oversized[0] = (byte) 0xFF;
        oversized[1] = (byte) 0xD8;
        oversized[2] = (byte) 0xFF;

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.submitChange(1L, "Tester", "Bio", oversized));
        assertTrue(ex.getMessage().contains("2 MiB"));
    }
}
