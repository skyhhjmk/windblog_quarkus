package com.biliwind.blog.service.ai.tool;

import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.service.ai.AiChatMessage;
import com.biliwind.blog.service.ai.AiProviderRequest;
import com.biliwind.blog.service.ai.AiResult;
import com.biliwind.blog.service.ai.AiToolCall;
import com.biliwind.blog.service.ai.AiToolContext;
import com.biliwind.blog.service.ai.AiToolDefinition;
import com.biliwind.blog.service.ai.OpenAiAiService;
import com.biliwind.blog.service.settings.SettingCatalogue;
import com.biliwind.blog.service.settings.SettingDefinition;
import com.biliwind.blog.service.settings.SettingKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolAndOneShotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersAiTopicMcpSettingDefinition() {
        SettingDefinition def = SettingCatalogue.findByKey(SettingKey.AI_TOPIC_MCP.key());
        assertNotNull(def);
        assertTrue(def.defaultValue().path("toolsEnabled").asBoolean(false));
        assertEquals(8, def.defaultValue().path("maxToolRounds").asInt());
        assertEquals("AUTO", def.defaultValue().path("webSearchProvider").asText());
        assertTrue(def.secretFields().contains("tavilyApiKey"));
        assertTrue(def.secretFields().contains("serperApiKey"));
    }

    @Test
    void openAiServiceExtractsToolCallsAndHandlesNullContent() throws Exception {
        OpenAiAiService service = new OpenAiAiService();
        var field = OpenAiAiService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(service, objectMapper);

        String responseJson = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_abc",
                        "type": "function",
                        "function": {
                          "name": "import_image_to_media",
                          "arguments": "{\\"imageUrl\\":\\"https://example.com/a.png\\",\\"alt\\":\\"demo\\"}"
                        }
                      }]
                    }
                  }]
                }
                """;
        JsonNode root = objectMapper.readTree(responseJson);

        Method extractText = OpenAiAiService.class.getDeclaredMethod("extractTextFromResponse", JsonNode.class);
        extractText.setAccessible(true);
        assertEquals("", extractText.invoke(service, root));

        Method extractTools = OpenAiAiService.class.getDeclaredMethod("extractToolCallsFromResponse", JsonNode.class);
        extractTools.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<AiToolCall> calls = (List<AiToolCall>) extractTools.invoke(service, root);
        assertEquals(1, calls.size());
        assertEquals("call_abc", calls.get(0).id());
        assertEquals("import_image_to_media", calls.get(0).name());
        assertTrue(calls.get(0).argumentsJson().contains("https://example.com/a.png"));
    }

    @Test
    void builtInToolsValidateContextAndMissingArguments() throws Exception {
        WebSearchTool webSearch = new WebSearchTool();
        webSearch.objectMapper = objectMapper;
        JsonNode blockedSearch = objectMapper.readTree(webSearch.execute(
                objectMapper.valueToTree(Map.of("query", "quarkus")),
                new AiToolContext(1L, 2L, false, true)));
        assertTrue(blockedSearch.has("error"));

        FetchWebPageTool fetchPage = new FetchWebPageTool();
        fetchPage.objectMapper = objectMapper;
        JsonNode missingUrl = objectMapper.readTree(fetchPage.execute(
                objectMapper.createObjectNode(),
                AiToolContext.forDraft(1L, 2L)));
        assertTrue(missingUrl.has("error"));

        ImportImageToMediaTool importTool = new ImportImageToMediaTool();
        importTool.objectMapper = objectMapper;
        JsonNode blockedImport = objectMapper.readTree(importTool.execute(
                objectMapper.valueToTree(Map.of("imageUrl", "https://example.com/img.png")),
                new AiToolContext(1L, 2L, true, false)));
        assertFalse(blockedImport.path("success").asBoolean(true));
        assertTrue(blockedImport.has("error"));
    }
}
