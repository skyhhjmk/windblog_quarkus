package com.biliwind.blog.filter;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
