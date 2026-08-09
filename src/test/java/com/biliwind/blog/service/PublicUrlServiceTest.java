package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicUrlServiceTest {

    @Test
    void shouldNormalizeConfiguredOriginAndBuildPaths() {
        PublicUrlService service = new PublicUrlService();
        service.configuredPublicUrl = " https://blog.example.com/// ";

        assertEquals("https://blog.example.com", service.getBaseUrl());
        assertEquals("https://blog.example.com/amp/post/hello", service.buildPath("amp/post/hello"));
    }

    @Test
    void shouldUseSafeLocalFallbackForBlankOrigin() {
        assertEquals("http://localhost:8080", PublicUrlService.normalize("  "));
    }
}
