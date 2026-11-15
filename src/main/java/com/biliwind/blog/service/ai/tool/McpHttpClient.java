package com.biliwind.blog.service.ai.tool;

import com.biliwind.blog.service.ai.AiToolDefinition;
import com.biliwind.blog.service.security.SafeExternalHttpService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight Streamable-HTTP / JSON-RPC 2.0 MCP client for external MCP servers.
 */
@ApplicationScoped
public class McpHttpClient {

    private static final Logger log = LoggerFactory.getLogger(McpHttpClient.class);

    public record RemoteMcpTool(AiMcpSettingsService.McpServerConfig server,
                                String remoteName,
                                AiToolDefinition definition) {
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Inject
    SafeExternalHttpService safeExternalHttpService;

    @Inject
    ObjectMapper objectMapper;

    public List<RemoteMcpTool> listTools(AiMcpSettingsService.McpServerConfig server) {
        if (server == null || !server.enabled() || server.url().isBlank()) {
            return List.of();
        }
        try {
            JsonNode result = rpcCall(server, "tools/list", Map.of(), 1);
            JsonNode toolsNode = result.path("tools");
            if (!toolsNode.isArray()) {
                return List.of();
            }
            List<RemoteMcpTool> tools = new ArrayList<>();
            String prefix = sanitizePrefix(server.name());
            for (JsonNode toolNode : toolsNode) {
                String remoteName = toolNode.path("name").asText("").trim();
                if (remoteName.isBlank()) continue;
                String description = toolNode.path("description").asText("");
                Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
                JsonNode inputSchema = toolNode.get("inputSchema");
                if (inputSchema != null && inputSchema.isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> converted = objectMapper.convertValue(inputSchema, Map.class);
                    schema = converted;
                }
                String exposedName = "mcp_" + prefix + "_" + remoteName.replaceAll("[^a-zA-Z0-9_-]", "_");
                if (exposedName.length() > 64) {
                    exposedName = exposedName.substring(0, 64);
                }
                AiToolDefinition def = new AiToolDefinition(exposedName,
                        "[MCP:" + server.name() + "] " + description, schema);
                tools.add(new RemoteMcpTool(server, remoteName, def));
            }
            return tools;
        } catch (Exception exception) {
            log.warn("连接 MCP 服务 {} 列出工具失败: {}", server.name(), exception.getMessage());
            return List.of();
        }
    }

    public String callTool(RemoteMcpTool tool, JsonNode arguments) {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", tool.remoteName());
            params.put("arguments", arguments == null || arguments.isNull()
                    ? Map.of() : objectMapper.convertValue(arguments, Map.class));
            JsonNode result = rpcCall(tool.server(), "tools/call", params, 2);
            JsonNode content = result.path("content");
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode part : content) {
                    if ("text".equalsIgnoreCase(part.path("type").asText())) {
                        if (!text.isEmpty()) text.append("\n");
                        text.append(part.path("text").asText(""));
                    }
                }
                if (!text.isEmpty()) {
                    return text.toString();
                }
            }
            return result.toString();
        } catch (Exception exception) {
            return "{\"error\":\"MCP 工具调用失败: " + safeJsonText(exception.getMessage()) + "\"}";
        }
    }

    private JsonNode rpcCall(AiMcpSettingsService.McpServerConfig server,
                             String method, Object params, int id) throws Exception {
        URI uri = safeExternalHttpService.validatePublicHttpUri(server.url());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (server.apiKey() != null && !server.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + server.apiKey());
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root.hasNonNull("error")) {
            throw new IllegalStateException(root.path("error").path("message").asText("MCP error"));
        }
        return root.path("result");
    }

    private static String sanitizePrefix(String name) {
        String cleaned = name.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        return cleaned.isBlank() ? "ext" : cleaned;
    }

    private static String safeJsonText(String text) {
        return text == null ? "" : text.replace("\"", "'");
    }
}
