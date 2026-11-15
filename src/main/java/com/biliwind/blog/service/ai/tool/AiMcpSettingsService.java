package com.biliwind.blog.service.ai.tool;

import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads AI topic writing tool & MCP configuration from {@link SettingKey#AI_TOPIC_MCP}.
 */
@ApplicationScoped
public class AiMcpSettingsService {

    public record McpServerConfig(String name, String url, String apiKey, boolean enabled) {
    }

    public record McpSettings(boolean toolsEnabled, int maxToolRounds, String webSearchProvider,
                              String searxngUrl, String tavilyApiKey, String serperApiKey,
                              List<McpServerConfig> mcpServers) {
    }

    @Inject
    SettingsReader settingsReader;

    @Inject
    ObjectMapper objectMapper;

    public McpSettings current() {
        JsonNode node = settingsReader.object(SettingKey.AI_TOPIC_MCP);
        boolean toolsEnabled = node == null || !node.has("toolsEnabled") || node.path("toolsEnabled").asBoolean(true);
        int maxRounds = node == null ? 8 : Math.max(1, Math.min(12, node.path("maxToolRounds").asInt(8)));
        String webSearchProvider = node == null ? "AUTO"
                : node.path("webSearchProvider").asText("AUTO").trim().toUpperCase();
        if (webSearchProvider.isBlank()) {
            webSearchProvider = "AUTO";
        }
        String searxngUrl = node == null ? "" : node.path("searxngUrl").asText("").trim();
        String tavilyApiKey = node == null ? "" : node.path("tavilyApiKey").asText("").trim();
        String serperApiKey = node == null ? "" : node.path("serperApiKey").asText("").trim();
        List<McpServerConfig> servers = parseMcpServers(node == null ? null : node.get("mcpServersJson"));
        return new McpSettings(toolsEnabled, maxRounds, webSearchProvider,
                searxngUrl, tavilyApiKey, serperApiKey, servers);
    }

    private List<McpServerConfig> parseMcpServers(JsonNode rawNode) {
        if (rawNode == null || rawNode.isNull()) {
            return List.of();
        }
        JsonNode array = rawNode;
        if (rawNode.isTextual()) {
            String text = rawNode.asText("").trim();
            if (text.isBlank()) {
                return List.of();
            }
            try {
                array = objectMapper.readTree(text);
            } catch (Exception ignored) {
                return List.of();
            }
        }
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<McpServerConfig> result = new ArrayList<>();
        for (JsonNode item : array) {
            if (item == null || !item.isObject()) {
                continue;
            }
            boolean enabled = !item.has("enabled") || item.path("enabled").asBoolean(true);
            String name = item.path("name").asText("").trim();
            String url = item.path("url").asText("").trim();
            String apiKey = item.path("apiKey").asText("").trim();
            if (enabled && !name.isBlank() && !url.isBlank()) {
                result.add(new McpServerConfig(name, url, apiKey, true));
            }
        }
        return result;
    }
}
