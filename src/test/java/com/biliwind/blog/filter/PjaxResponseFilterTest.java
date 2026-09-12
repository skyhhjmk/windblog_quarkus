package com.biliwind.blog.filter;

import com.biliwind.blog.common.helper.PjaxHelper;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PjaxResponseFilterTest {

    @Test
    void shouldAppendPjaxToExistingVaryHeader() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("Vary", "Accept-Language, Cookie");

        PjaxResponseFilter.appendVary(headers, PjaxHelper.PJAX_HEADER);

        assertEquals("Accept-Language, Cookie, X-PJAX", headers.getFirst("Vary"));
    }

    @Test
    void shouldNotDuplicatePjaxInVaryHeader() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("Vary", "Accept-Language, X-PJAX");

        PjaxResponseFilter.appendVary(headers, PjaxHelper.PJAX_HEADER);

        assertEquals("Accept-Language, X-PJAX", headers.getFirst("Vary"));
    }

    @Test
    void shouldDisableCachingForHtmlResponses() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        PjaxResponseFilter.applyNoStore(headers);

        assertEquals("no-cache, no-store, must-revalidate", headers.getFirst("Cache-Control"));
        assertEquals("no-cache", headers.getFirst("Pragma"));
        assertEquals("0", headers.getFirst("Expires"));
    }
}
