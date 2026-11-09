package com.biliwind.blog.filter;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedContentCacheFilterTest {

    @Test
    void shouldApplyNoStoreHeadersToProtectedResponses() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        ProtectedContentCacheFilter.applyNoStore(headers);

        assertEquals("no-cache, no-store, must-revalidate", headers.getFirst("Cache-Control"));
        assertEquals("no-cache", headers.getFirst("Pragma"));
        assertEquals("0", headers.getFirst("Expires"));
        assertEquals("Cookie", headers.getFirst("Vary"));
    }

    @Test
    void shouldIdentifyNoStoreConditions() {
        // 4xx / 5xx 错误必须 no-store
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(404, "/", Collections.emptyList()));
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(500, "/post/test", Collections.emptyList()));
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(502, "/assets/app.js", Collections.emptyList()));

        // 管理后台 API 必须 no-store
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(200, "/api/admin/system/monitor", Collections.emptyList()));
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(200, "api/admin/posts", Collections.emptyList()));

        // 用户中心及受保护端点必须 no-store
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(200, "/api/user/post/content", Collections.emptyList()));
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(200, "/user/profile", Collections.emptyList()));
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(200, "/api/internal/sync", Collections.emptyList()));

        // 包含敏感 Set-Cookie 必须 no-store
        assertTrue(ProtectedContentCacheFilter.shouldApplyNoStore(200, "/", List.of("user_token=abc123456; Path=/; HttpOnly")));

        // 正常静态资源或匿名公开文章不触发强制 no-store
        assertFalse(ProtectedContentCacheFilter.shouldApplyNoStore(200, "/assets/img/avatar.svg", Collections.emptyList()));
        assertFalse(ProtectedContentCacheFilter.shouldApplyNoStore(200, "/post/welcome-to-windblog", Collections.emptyList()));
        assertFalse(ProtectedContentCacheFilter.shouldApplyNoStore(200, "/", Collections.emptyList()));
    }
}
