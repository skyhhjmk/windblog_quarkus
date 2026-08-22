package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedDataImageServiceTest {
    @Test
    void parsesBase64ImageAndCreatesStableHash() {
        String value = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        EmbeddedDataImageService.ParsedImage parsed = EmbeddedDataImageService.parse(value);
        assertEquals("image/png", parsed.mimeType());
        assertEquals("png", parsed.extension());
        assertEquals(3, parsed.bytes().length);
        assertTrue(parsed.fileName().contains(parsed.sha256()));
    }

    @Test
    void preservesPlusInPercentEncodedDataAndSanitizesSvg() {
        String svg = "<svg onload=alert(1)><script>alert(2)</script><text>+</text></svg>";
        String value = "data:image/svg+xml," + java.net.URLEncoder.encode(svg, StandardCharsets.UTF_8)
                .replace("+", "%20");
        EmbeddedDataImageService.ParsedImage parsed = EmbeddedDataImageService.parse(value);
        String result = new String(parsed.bytes(), StandardCharsets.UTF_8);
        assertEquals("image/svg+xml", parsed.mimeType());
        assertTrue(!result.contains("script") && !result.contains("onload"));
        assertTrue(result.contains("+"));
    }

    @Test
    void rejectsNonImageAndOversizedDataUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddedDataImageService.parse("data:text/plain;base64,SGk="));
        String oversized = "data:image/png;base64," + Base64.getEncoder().encodeToString(
                new byte[(int) EmbeddedDataImageService.MAX_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> EmbeddedDataImageService.parse(oversized));
    }
}
