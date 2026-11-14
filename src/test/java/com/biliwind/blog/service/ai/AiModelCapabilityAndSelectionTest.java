package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiModelCapabilityAndSelectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesModelCapabilitiesFromConfigJson() {
        AiProviderConfig config = new AiProviderConfig();
        config.model = "gpt-4o";
        config.config = """
                {
                  "modelCapabilities": {
                    "gpt-4o": {
                      "contextWindow": 128000,
                      "inputModalities": ["TEXT", "IMAGE"],
                      "outputModalities": ["TEXT"]
                    }
                  }
                }
                """;

        AiModelCapability capability = AiModelCapability.resolve(config, null, objectMapper);
        assertNotNull(capability);
        assertEquals(128000, capability.contextWindow());
        assertTrue(capability.supportsInput("TEXT"));
        assertTrue(capability.supportsInput("IMAGE"));
        assertFalse(capability.supportsInput("AUDIO"));
        assertTrue(capability.supportsOutput("TEXT"));
        assertFalse(capability.supportsOutput("IMAGE"));
    }

    @Test
    void avatarAuditSelectsOnlyImageCapableModel() {
        AiSelectionService selectionService = new AiSelectionService();
        selectionService.objectMapper = objectMapper;

        AiProviderConfig textOnly = new AiProviderConfig();
        textOnly.id = 1L;
        textOnly.type = AiConfigType.PROVIDER;
        textOnly.enabled = true;
        textOnly.model = "deepseek-chat";
        textOnly.config = """
                {
                  "modelCapabilities": {
                    "deepseek-chat": {
                      "contextWindow": 64000,
                      "inputModalities": ["TEXT"],
                      "outputModalities": ["TEXT"]
                    }
                  }
                }
                """;

        AiProviderConfig multimodal = new AiProviderConfig();
        multimodal.id = 2L;
        multimodal.type = AiConfigType.PROVIDER;
        multimodal.enabled = true;
        multimodal.model = "gpt-4o";
        multimodal.config = """
                {
                  "modelCapabilities": {
                    "gpt-4o": {
                      "contextWindow": 128000,
                      "inputModalities": ["TEXT", "IMAGE"],
                      "outputModalities": ["TEXT"]
                    }
                  }
                }
                """;

        assertNull(selectionService.select("avatar_audit", List.of(textOnly)));
        AiProviderConfig chosen = selectionService.select("avatar_audit", List.of(textOnly, multimodal));
        assertNotNull(chosen);
        assertEquals(2L, chosen.id);
    }
}
