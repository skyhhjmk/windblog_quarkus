package com.biliwind.blog.service.ai.tool;

import com.biliwind.blog.service.ai.AiTool;
import com.biliwind.blog.service.ai.AiToolCall;
import com.biliwind.blog.service.ai.AiToolContext;
import com.biliwind.blog.service.ai.AiToolDefinition;
import com.biliwind.blog.service.ai.AiToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects built-in {@link AiTool} beans and configured external MCP server tools,
 * and dispatches model tool calls safely.
 */
@ApplicationScoped
public class AiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);

    public record ToolBundle(List<AiToolDefinition> definitions, AiToolExecutor executor, int maxRounds) {
    }

    @Inject
    Instance<AiTool> builtInTools;

    @Inject
    AiMcpSettingsService mcpSettingsService;

    @Inject
    McpHttpClient mcpHttpClient;

    @Inject
    ObjectMapper objectMapper;

    public ToolBundle resolveBundle() {
        AiMcpSettingsService.McpSettings settings = mcpSettingsService.current();
        if (!settings.toolsEnabled()) {
            return new ToolBundle(List.of(), null, 1);
        }
        Map<String, AiTool> localByName = new LinkedHashMap<>();
        List<AiToolDefinition> definitions = new ArrayList<>();
        for (AiTool tool : builtInTools) {
            AiToolDefinition def = tool.definition();
            if (def != null && !def.name().isBlank()) {
                localByName.put(def.name(), tool);
                definitions.add(def);
            }
        }
        Map<String, McpHttpClient.RemoteMcpTool> remoteByName = new LinkedHashMap<>();
        for (AiMcpSettingsService.McpServerConfig server : settings.mcpServers()) {
            for (McpHttpClient.RemoteMcpTool remoteTool : mcpHttpClient.listTools(server)) {
                String name = remoteTool.definition().name();
                if (!localByName.containsKey(name) && !remoteByName.containsKey(name)) {
                    remoteByName.put(name, remoteTool);
                    definitions.add(remoteTool.definition());
                }
            }
        }
        AiToolExecutor executor = (call, context) -> executeCall(call, context, localByName, remoteByName);
        return new ToolBundle(definitions, executor, settings.maxToolRounds());
    }

    private String executeCall(AiToolCall call, AiToolContext context,
                               Map<String, AiTool> localByName,
                               Map<String, McpHttpClient.RemoteMcpTool> remoteByName) {
        try {
            JsonNode args = parseArguments(call.argumentsJson());
            AiTool local = localByName.get(call.name());
            if (local != null) {
                return local.execute(args, context);
            }
            McpHttpClient.RemoteMcpTool remote = remoteByName.get(call.name());
            if (remote != null) {
                return mcpHttpClient.callTool(remote, args);
            }
            return objectMapper.writeValueAsString(Map.of("error", "未知工具: " + call.name()));
        } catch (Exception exception) {
            log.warn("执行 AI 工具 {} 失败: {}", call.name(), exception.getMessage());
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "error", exception.getMessage() == null ? "工具执行失败" : exception.getMessage()));
            } catch (Exception ignored) {
                return "{\"error\":\"工具执行失败\"}";
            }
        }
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(argumentsJson);
            return parsed == null || parsed.isNull() ? objectMapper.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }
}
