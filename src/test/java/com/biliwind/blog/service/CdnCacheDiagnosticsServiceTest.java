package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CdnCacheDiagnosticsServiceTest {

    @Test
    void shouldProvideExpectedRecommendations() {
        CdnCacheDiagnosticsService service = new CdnCacheDiagnosticsService();
        Map<String, Object> rec = service.getRecommendations();

        assertNotNull(rec);
        @SuppressWarnings("unchecked")
        List<String> cacheKeyCookies = (List<String>) rec.get("cacheKeyCookies");
        assertTrue(cacheKeyCookies.contains("user_lang_v2"), "必须推荐把 user_lang_v2 加入 CDN Cache Key");

        @SuppressWarnings("unchecked")
        List<String> bypassCookies = (List<String>) rec.get("bypassCookies");
        assertTrue(bypassCookies.contains("user_token"), "必须推荐将 user_token 设为 Bypass CDN 缓存");
        assertTrue(bypassCookies.contains("admin_jwt"), "必须推荐将 admin_jwt 设为 Bypass CDN 缓存");

        @SuppressWarnings("unchecked")
        List<String> bypassPaths = (List<String>) rec.get("bypassPaths");
        assertTrue(bypassPaths.contains("/api/admin/*"));
        assertTrue(bypassPaths.contains("/api/user/*"));
        assertTrue(bypassPaths.contains("/q/*"));
    }
}
