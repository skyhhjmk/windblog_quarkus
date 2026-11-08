package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThumbHashHelperTest {

    @Test
    void testEncodeAndDecodeOpaqueImage() {
        int w = 20;
        int h = 10;
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < w * h * 4; i += 4) {
            rgba[i] = (byte) 240;     // R
            rgba[i + 1] = (byte) 40;  // G
            rgba[i + 2] = (byte) 20;  // B
            rgba[i + 3] = (byte) 255; // A
        }

        String base64 = ThumbHashHelper.rgbaToThumbHashBase64(w, h, rgba);
        assertNotNull(base64);
        assertFalse(base64.isBlank());
        assertTrue(base64.length() >= 20 && base64.length() <= 50,
                "Base64 length should be around 25-35 chars: " + base64.length());

        byte[] rawHash = ThumbHashHelper.fromBase64(base64);
        assertNotNull(rawHash);

        float aspectRatio = ThumbHashHelper.thumbHashToApproximateAspectRatio(rawHash);
        assertTrue(aspectRatio > 1.2f, "Should detect landscape aspect ratio: " + aspectRatio);

        ThumbHashHelper.Image decoded = ThumbHashHelper.thumbHashToRGBA(rawHash);
        assertNotNull(decoded);
        assertTrue(decoded.width > 0);
        assertTrue(decoded.height > 0);
        assertEquals(decoded.width * decoded.height * 4, decoded.rgba.length);

        // Verify decoded pixels have higher red component than green/blue
        int r = decoded.rgba[0] & 0xFF;
        int g = decoded.rgba[1] & 0xFF;
        int b = decoded.rgba[2] & 0xFF;
        assertTrue(r > g && r > b, "Red channel should dominate: r=" + r + " g=" + g + " b=" + b);
    }

    @Test
    void testDimensionsValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                ThumbHashHelper.rgbaToThumbHash(101, 50, new byte[101 * 50 * 4]));
        assertThrows(IllegalArgumentException.class, () ->
                ThumbHashHelper.rgbaToThumbHash(0, 50, new byte[0]));
    }
}
