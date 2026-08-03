package com.biliwind.blog.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveMessageSanitizerTest {

    @Test
    void shouldRedactCredentialsAndBoundMessageLength() {
        String message = SensitiveMessageSanitizer.sanitize(
                "jdbc:postgresql://user:password@db/app?secret=top-secret " + "x".repeat(600));

        assertFalse(message.contains("password@"));
        assertFalse(message.contains("top-secret"));
        assertTrue(message.length() <= 503);
        assertTrue(message.endsWith("..."));
    }
}
