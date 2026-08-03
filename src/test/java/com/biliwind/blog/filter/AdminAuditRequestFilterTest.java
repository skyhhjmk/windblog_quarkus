package com.biliwind.blog.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuditRequestFilterTest {

    @Test
    void shouldKeepSafeRequestId() {
        assertEquals("request-2026_08.03", AdminAuditRequestFilter.resolveRequestId("request-2026_08.03"));
    }

    @Test
    void shouldReplaceMissingOrUnsafeRequestId() {
        String missing = AdminAuditRequestFilter.resolveRequestId(null);
        String unsafe = AdminAuditRequestFilter.resolveRequestId("x\r\nSet-Cookie: leaked");

        assertNotNull(missing);
        assertNotEquals("x\r\nSet-Cookie: leaked", unsafe);
        assertTrue(missing.matches("[0-9a-f-]{36}"));
        assertTrue(unsafe.matches("[0-9a-f-]{36}"));
    }
}
