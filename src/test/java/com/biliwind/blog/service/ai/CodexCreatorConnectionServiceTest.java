package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexCreatorConnectionServiceTest {

    @Test
    void shouldNormalizeHttpEndpointsWithoutChangingTheirPath() {
        assertEquals("http://codex-creator:8681", CodexCreatorConnectionService.normalizeEndpoint(
                " http://codex-creator:8681/// "));
        assertEquals("https://ai.example.com/codex", CodexCreatorConnectionService.normalizeEndpoint(
                "https://ai.example.com/codex/"));
    }

    @Test
    void shouldRejectEndpointCredentialsAndRequestParts() {
        assertThrows(IllegalArgumentException.class,
                () -> CodexCreatorConnectionService.normalizeEndpoint(""));
        assertThrows(IllegalArgumentException.class,
                () -> CodexCreatorConnectionService.normalizeEndpoint("ftp://ai.example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> CodexCreatorConnectionService.normalizeEndpoint("https://user:pass@ai.example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> CodexCreatorConnectionService.normalizeEndpoint("https://ai.example.com?token=secret"));
    }
}
