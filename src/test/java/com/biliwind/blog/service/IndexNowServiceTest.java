package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndexNowServiceTest {

    @Test
    void validatesIndexNowKeysPerSpec() {
        assertTrue(IndexNowService.isValidKey("a1b2c3d4e5f60718293a4b5c6d7e8f90"));
        assertTrue(IndexNowService.isValidKey("windblog-key-2026"));
        assertFalse(IndexNowService.isValidKey(null));
        assertFalse(IndexNowService.isValidKey(""));
        assertFalse(IndexNowService.isValidKey("robots")); // 6 chars < 8
        assertFalse(IndexNowService.isValidKey("invalid_underscore_key"));
        assertFalse(IndexNowService.isValidKey("has space 1234"));
    }
}
