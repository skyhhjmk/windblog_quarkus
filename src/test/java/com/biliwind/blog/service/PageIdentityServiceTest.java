package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageIdentityServiceTest {

    @Test
    void sanitizeCanonicalQueryRetainsOnlyValidPaginationBeyondFirstPage() {
        assertEquals("", PageIdentityService.sanitizeCanonicalQuery(null));
        assertEquals("", PageIdentityService.sanitizeCanonicalQuery(""));
        assertEquals("", PageIdentityService.sanitizeCanonicalQuery("utm_source=twitter&fbclid=123"));
        assertEquals("", PageIdentityService.sanitizeCanonicalQuery("page=1&utm_medium=social"));
        assertEquals("", PageIdentityService.sanitizeCanonicalQuery("page=abc"));
        assertEquals("page=2", PageIdentityService.sanitizeCanonicalQuery("utm_source=x&page=2&ref=linuxdo"));
    }
}
