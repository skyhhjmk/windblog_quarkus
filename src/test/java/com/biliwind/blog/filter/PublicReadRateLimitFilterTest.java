package com.biliwind.blog.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicReadRateLimitFilterTest {

    @Test
    void shouldClassifyPublicReadRoutes() {
        assertEquals(PublicReadRateLimitFilter.ReadRoute.HOME,
                PublicReadRateLimitFilter.classify("/"));
        assertEquals(PublicReadRateLimitFilter.ReadRoute.POST,
                PublicReadRateLimitFilter.classify("post/example"));
        assertEquals(PublicReadRateLimitFilter.ReadRoute.POST,
                PublicReadRateLimitFilter.classify("/zh/post/example"));
        assertEquals(PublicReadRateLimitFilter.ReadRoute.SEARCH,
                PublicReadRateLimitFilter.classify("/search"));
        assertEquals(PublicReadRateLimitFilter.ReadRoute.BROWSE,
                PublicReadRateLimitFilter.classify("/category/security"));
        assertEquals(PublicReadRateLimitFilter.ReadRoute.NONE,
                PublicReadRateLimitFilter.classify("/api/user/post/content"));
    }

    @Test
    void shouldCreateStableOpaqueIpKeys() {
        String first = PublicReadRateLimitFilter.digest("203.0.113.10");
        String second = PublicReadRateLimitFilter.digest("203.0.113.10");

        assertEquals(first, second);
        assertNotEquals("203.0.113.10", first);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }
}
