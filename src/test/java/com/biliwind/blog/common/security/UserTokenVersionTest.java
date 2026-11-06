package com.biliwind.blog.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTokenVersionTest {

    @Test
    void shouldAcceptCurrentAndLegacyTokenGenerations() {
        assertTrue(UserTokenVerifier.isCurrentTokenVersion(null, 0L));
        assertTrue(UserTokenVerifier.isCurrentTokenVersion(0L, 0L));
        assertTrue(UserTokenVerifier.isCurrentTokenVersion(3L, 3L));
    }

    @Test
    void shouldRejectTokensInvalidatedByLogoutOrPasswordChange() {
        assertFalse(UserTokenVerifier.isCurrentTokenVersion(0L, 1L));
        assertFalse(UserTokenVerifier.isCurrentTokenVersion(2L, 3L));
    }
}
