package com.biliwind.blog.service.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AdminTokenRevocationServiceTest {

    @Test
    void shouldHashTokenWithoutPersistingTheOriginalValue() {
        String token = "admin-token-for-test";
        String hash = AdminTokenRevocationService.hashToken(token);

        assertEquals(64, hash.length());
        assertNotEquals(token, hash);
        assertFalse(hash.contains(token));
    }
}
