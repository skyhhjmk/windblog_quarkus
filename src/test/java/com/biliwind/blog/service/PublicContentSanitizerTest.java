package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicContentSanitizerTest {

    private final PublicContentSanitizer sanitizer = new PublicContentSanitizer();

    @Test
    void shouldRemoveActiveHtmlAndNormalizeLinks() {
        String html = sanitizer.sanitize("<script>alert(1)</script><a href=\"https://example.com\">link</a>"
                + "<img src=\"javascript:alert(1)\"><div style=\"background:url(https://evil.test)\">text</div>");

        assertFalse(html.contains("script"));
        assertFalse(html.contains("javascript:"));
        assertFalse(html.contains("style="));
        assertTrue(html.contains("rel=\"nofollow noopener noreferrer\""));
    }

    @Test
    void shouldRemoveExternalImagesUnlessTheirHostIsConfigured() {
        String html = sanitizer.sanitize("<img src=\"https://evil.example/image.png\"><img src=\"//evil.example/other.png\">");

        assertFalse(html.contains("evil.example"));
    }

    @Test
    void shouldRemoveProtocolRelativeImageFromUnknownHost() {
        String html = sanitizer.sanitize("<img src=\"//unknown.example/private.png\">");

        assertFalse(html.contains("unknown.example"));
    }

    @Test
    void shouldRemoveCredentialBearingImageUrls() {
        String html = sanitizer.sanitize("<img src=\"http://user:password@localhost/private.png\">");

        assertFalse(html.contains("password@"));
    }

    @Test
    void shouldRemoveRichContentActiveAttributesAndDataImages() {
        String html = sanitizer.sanitize("<svg onload=alert(1)><script>alert(1)</script></svg>"
                + "<img src=\"data:image/svg+xml;base64,AAAA\"><a href=\"javascript:alert(1)\">bad</a>");

        assertFalse(html.contains("onload"));
        assertFalse(html.contains("script"));
        assertFalse(html.contains("data:image"));
        assertFalse(html.contains("javascript:"));
    }
}
