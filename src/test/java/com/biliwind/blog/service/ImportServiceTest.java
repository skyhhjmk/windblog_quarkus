package com.biliwind.blog.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportServiceTest {

    @Test
    void shouldRedactCredentialsFromImportErrors() {
        String message = ImportService.sanitizeErrorMessage(
                "connect jdbc:postgresql://import-user:plain-secret@db.example/app?password=url-secret&apiKey=key-secret");

        assertFalse(message.contains("plain-secret"));
        assertFalse(message.contains("url-secret"));
        assertFalse(message.contains("key-secret"));
        assertTrue(message.contains("[REDACTED]"));
    }

    @Test
    void shouldBoundImportErrorLength() {
        String message = ImportService.sanitizeErrorMessage("x".repeat(600));

        assertTrue(message.length() <= 503);
        assertTrue(message.endsWith("..."));
    }

    @Test
    void shouldRedactCredentialsWhenAnImportUrlIsIncludedInAnEvent() {
        String message = ImportService.sanitizeErrorMessage(
                "同步媒体资源: https://sync-user:sync-secret@assets.example/file.png?token=url-token");

        assertFalse(message.contains("sync-secret"));
        assertFalse(message.contains("url-token"));
        assertTrue(message.contains("[REDACTED]"));
    }
}
