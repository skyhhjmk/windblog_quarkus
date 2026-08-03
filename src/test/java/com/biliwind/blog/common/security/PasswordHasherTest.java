package com.biliwind.blog.common.security;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PasswordHasher 单元测试
 * 测试密码哈希和验证功能
 */
@QuarkusTest
class PasswordHasherTest {

    @Inject
    PasswordHasher passwordHasher;

    @Test
    void shouldHashPasswordWithCorrectFormat() {
        String rawPassword = "mySecretPassword123";
        String hashed = passwordHasher.hash(rawPassword);

        assertNotNull(hashed);
        assertTrue(hashed.startsWith("{pbkdf2-sha256}$"));
        String[] parts = hashed.split("\\$");
        assertEquals(4, parts.length);
    }

    @Test
    void shouldMatchCorrectPassword() {
        String rawPassword = "testPassword456";
        String hashed = passwordHasher.hash(rawPassword);

        assertTrue(passwordHasher.matches(rawPassword, hashed));
    }

    @Test
    void shouldNotMatchIncorrectPassword() {
        String rawPassword = "correctPassword";
        String wrongPassword = "wrongPassword";
        String hashed = passwordHasher.hash(rawPassword);

        assertFalse(passwordHasher.matches(wrongPassword, hashed));
    }

    @Test
    void shouldReturnFalseWhenMatchingNullPassword() {
        String hashed = passwordHasher.hash("somePassword");

        assertFalse(passwordHasher.matches(null, hashed));
    }

    @Test
    void shouldReturnFalseWhenMatchingAgainstNullHash() {
        assertFalse(passwordHasher.matches("somePassword", null));
    }

    @Test
    void shouldReturnFalseWhenMatchingAgainstEmptyHash() {
        assertFalse(passwordHasher.matches("somePassword", ""));
    }

    @Test
    void shouldReturnFalseWhenMatchingAgainstBlankHash() {
        assertFalse(passwordHasher.matches("somePassword", "   "));
    }

    @Test
    void shouldIdentifyHashedFormat() {
        String hashed = passwordHasher.hash("password");

        assertTrue(passwordHasher.isHashedFormat(hashed));
    }

    @Test
    void shouldNotIdentifyPlainTextAsHashedFormat() {
        assertFalse(passwordHasher.isHashedFormat("plainTextPassword"));
    }

    @Test
    void shouldNotIdentifyNullAsHashedFormat() {
        assertFalse(passwordHasher.isHashedFormat(null));
    }

    @Test
    void shouldNotIdentifyEmptyStringAsHashedFormat() {
        assertFalse(passwordHasher.isHashedFormat(""));
    }

    @Test
    void shouldNotIdentifySimilarButInvalidFormatAsHashed() {
        assertFalse(passwordHasher.isHashedFormat("{pbkdf2-sha256}"));
        assertFalse(passwordHasher.isHashedFormat("{pbkdf2-sha256}$"));
    }

    @Test
    void shouldRejectPlainTextComparisonWhenNotHashedFormat() {
        String plainText = "plainPassword";

        assertFalse(passwordHasher.matches(plainText, plainText));
    }

    @Test
    void shouldGenerateDifferentHashesForSamePassword() {
        String rawPassword = "samePassword";
        String hash1 = passwordHasher.hash(rawPassword);
        String hash2 = passwordHasher.hash(rawPassword);

        assertNotEquals(hash1, hash2);
        assertTrue(passwordHasher.matches(rawPassword, hash1));
        assertTrue(passwordHasher.matches(rawPassword, hash2));
    }

    @Test
    void shouldReturnFalseForMalformedHash() {
        String malformedHash = "{pbkdf2-sha256}$invalid$notBase64$notBase64";

        assertFalse(passwordHasher.matches("password", malformedHash));
    }

    @Test
    void shouldReturnFalseForHashWithWrongNumberOfParts() {
        String twoParts = "{pbkdf2-sha256}$1000";
        String threeParts = "{pbkdf2-sha256}$1000$salt";

        assertFalse(passwordHasher.matches("password", twoParts));
        assertFalse(passwordHasher.matches("password", threeParts));
    }
}
