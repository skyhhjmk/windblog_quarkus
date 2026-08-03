package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderConfigSanitizerTest {

    @Test
    void shouldMaskNestedProviderSecrets() {
        String masked = AiProviderConfigSanitizer.maskConfig(
                "{\"proxy_host\":\"proxy.example.com\",\"proxy_password\":\"secret\","
                        + "\"nested\":{\"apiKey\":\"key-value\",\"enabled\":true}}");

        assertFalse(masked.contains("secret"));
        assertFalse(masked.contains("key-value"));
        assertTrue(masked.contains("proxy.example.com"));
        assertTrue(masked.contains("******"));
    }

    @Test
    void shouldPreserveStoredSecretsWhenMaskedConfigIsSubmitted() {
        String merged = AiProviderConfigSanitizer.mergeMaskedSecrets(
                "{\"proxy_password\":\"******\",\"proxy_host\":\"new.example.com\"}",
                "{\"proxy_password\":\"stored-secret\",\"proxy_host\":\"old.example.com\"}");

        assertTrue(merged.contains("stored-secret"));
        assertTrue(merged.contains("new.example.com"));
        assertEquals(1, merged.split("stored-secret", -1).length - 1);
    }
}
