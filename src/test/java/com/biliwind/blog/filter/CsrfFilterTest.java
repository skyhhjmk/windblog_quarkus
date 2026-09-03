package com.biliwind.blog.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfFilterTest {

    @Test
    void shouldProtectCookieAuthenticatedWriteEndpoints() {
        String[] protectedPaths = {
                "/user/api/register",
                "/user/api/login",
                "/user/api/logout",
                "/api/comments",
                "/api/user/post/buy/7",
                "/api/user/post/password/7",
                "/api/user/buy-post/7",
                "/api/user/wallet/check-in",
                "/api/user/repost/licenses",
                "/user/api/posts",
                "/user/api/posts/7/submit",
                "/api/user/favorites/post/demo",
                "/user/api/notifications/7/read",
                "/api/link-applications"
        };

        for (String path : protectedPaths) {
            assertTrue(CsrfFilter.requiresCsrf("POST", path), path);
        }
        assertTrue(CsrfFilter.requiresCsrf("PUT", "/api/comments/7"));
        assertTrue(CsrfFilter.requiresCsrf("DELETE", "/api/comments/7"));
        assertTrue(CsrfFilter.requiresCsrf("PATCH", "/api/user/profile"));
    }

    @Test
    void shouldLeaveSafeAndBearerOnlyEndpointsOutsideCookieCsrfBoundary() {
        assertFalse(CsrfFilter.requiresCsrf("GET", "/api/comments"));
        assertFalse(CsrfFilter.requiresCsrf("OPTIONS", "/api/comments"));
        assertFalse(CsrfFilter.requiresCsrf("POST", "/api/admin/posts/7/publish"));
        assertFalse(CsrfFilter.requiresCsrf("POST", "/api/internal/integrations/codex-creator/content"));
        assertFalse(CsrfFilter.requiresCsrf("POST", "/health"));
        assertFalse(CsrfFilter.requiresCsrf("POST", null));
        assertTrue(CsrfFilter.requiresCsrf("post", "/api/comments"));
    }
}
