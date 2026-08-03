package com.biliwind.blog.filter;

import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfFilterTest {

    @Test
    void shouldMarkCacheableHtmlAsNonShareableWhenIssuingToken() {
        assertTrue(CsrfFilter.isCacheableHtmlResponse(MediaType.TEXT_HTML_TYPE, "public, max-age=60"));
        assertTrue(CsrfFilter.isCacheableHtmlResponse(MediaType.TEXT_HTML_TYPE, "s-maxage=60"));
    }

    @Test
    void shouldNotChangePrivateOrNonHtmlResponses() {
        assertFalse(CsrfFilter.isCacheableHtmlResponse(MediaType.TEXT_HTML_TYPE, "no-store"));
        assertFalse(CsrfFilter.isCacheableHtmlResponse(MediaType.APPLICATION_JSON_TYPE, "public, max-age=60"));
    }
}
