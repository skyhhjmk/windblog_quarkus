package com.biliwind.blog.controller;

import com.biliwind.blog.service.PublicToolboxService;
import com.biliwind.blog.service.PublicUrlService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless public Model Context Protocol (MCP) JSON-RPC 2.0 endpoint
 * exposing WindBlog's unauthenticated developer toolbox utilities.
 */
@Path("/api/public/mcp")
@ApplicationScoped
public class McpPublicResource {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    @Inject
    PublicToolboxService toolboxService;

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    ObjectMapper objectMapper;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response info() {
        String baseUrl = publicUrlService.getBaseUrl();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "windblog-public-tools");
        info.put("protocol", "mcp-jsonrpc-2.0");
        info.put("protocolVersion", PROTOCOL_VERSION);
        info.put("endpoint", baseUrl + "/api/public/mcp");
        info.put("transport", "stateless-http-post");
        info.put("tools", toolDefinitions());
        return Response.ok(info)
                .header("Cache-Control", "public, max-age=300")
                .build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleRpc(JsonNode request) {
        if (request == null || !request.isObject()) {
            return rpcError(null, -32600, "Invalid Request");
        }
        JsonNode idNode = request.get("id");
        Object id = idNode == null || idNode.isNull() ? null
                : (idNode.isNumber() ? idNode.numberValue() : idNode.asText());
        String method = request.path("method").asText("");

        if ("notifications/initialized".equals(method)) {
            return Response.noContent().build();
        }

        return switch (method) {
            case "initialize" -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", PROTOCOL_VERSION);
                result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
                result.put("serverInfo", Map.of("name", "windblog-public-tools", "version", "1.0.0"));
                yield rpcResult(id, result);
            }
            case "ping" -> rpcResult(id, Map.of());
            case "tools/list" -> rpcResult(id, Map.of("tools", toolDefinitions()));
            case "tools/call" -> handleToolCall(id, request.path("params"));
            default -> rpcError(id, -32601, "Method not found: " + method);
        };
    }

    private Response handleToolCall(Object id, JsonNode params) {
        if (params == null || !params.isObject()) {
            return rpcError(id, -32602, "params object is required");
        }
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        try {
            Map<String, Object> payload = switch (name) {
                case "subnet_calc" -> toolboxService.calculateSubnet(args.path("cidr").asText(""));
                case "timestamp_convert" -> toolboxService.convertTimestamp(args.path("value").asText("now"));
                case "cron_next_runs" -> toolboxService.nextCronRuns(
                        args.path("expr").asText(""),
                        args.path("count").asInt(5));
                case "hash_compute" -> toolboxService.computeHash(
                        args.path("text").isTextual() ? args.path("text").asText() : null,
                        args.path("algorithm").asText("SHA-256"));
                case "token_estimate" -> toolboxService.estimateTokens(args.path("text").asText(""));
                case "list_public_tools" -> Map.of(
                        "baseUrl", publicUrlService.getBaseUrl(),
                        "tools", ToolsController.allTools().stream()
                                .map(t -> Map.of(
                                        "id", t.id(),
                                        "name", t.name(),
                                        "tag", t.tag(),
                                        "url", publicUrlService.getBaseUrl() + t.url()))
                                .toList());
                default -> throw new UnknownMcpToolException("Unknown tool: " + name);
            };
            String jsonText = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            return rpcResult(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", jsonText)),
                    "isError", false
            ));
        } catch (UnknownMcpToolException e) {
            return rpcError(id, -32602, e.getMessage());
        } catch (IllegalArgumentException e) {
            return rpcResult(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())),
                    "isError", true
            ));
        } catch (JsonProcessingException e) {
            return rpcError(id, -32603, "Serialization error");
        }
    }

    private List<Map<String, Object>> toolDefinitions() {
        return List.of(
                toolDef("subnet_calc",
                        "Calculate IPv4/IPv6 CIDR network address, broadcast, netmask, wildcard mask and usable host range.",
                        Map.of(
                                "type", "object",
                                "required", List.of("cidr"),
                                "properties", Map.of("cidr", Map.of("type", "string", "description", "CIDR notation, e.g. 192.168.1.10/24"))
                        )),
                toolDef("timestamp_convert",
                        "Convert Unix epoch seconds/milliseconds, ISO-8601 timestamps, or 'now' across UTC and Asia/Shanghai.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("value", Map.of("type", "string", "description", "Epoch seconds/millis, ISO-8601 string, or 'now'"))
                        )),
                toolDef("cron_next_runs",
                        "Parse a 5-field or 6-field Cron expression and calculate upcoming trigger timestamps.",
                        Map.of(
                                "type", "object",
                                "required", List.of("expr"),
                                "properties", Map.of(
                                        "expr", Map.of("type", "string", "description", "Cron expression, e.g. */5 * * * *"),
                                        "count", Map.of("type", "integer", "default", 5, "description", "Number of upcoming runs (1-10)")
                                )
                        )),
                toolDef("hash_compute",
                        "Compute MD5, SHA-1, SHA-256, or SHA-512 digest for UTF-8 text.",
                        Map.of(
                                "type", "object",
                                "required", List.of("text"),
                                "properties", Map.of(
                                        "text", Map.of("type", "string", "description", "Input text"),
                                        "algorithm", Map.of("type", "string", "enum", List.of("MD5", "SHA-1", "SHA-256", "SHA-512"), "default", "SHA-256")
                                )
                        )),
                toolDef("token_estimate",
                        "Estimate LLM token counts, CJK/ASCII character counts, word count and lines for a prompt.",
                        Map.of(
                                "type", "object",
                                "required", List.of("text"),
                                "properties", Map.of("text", Map.of("type", "string", "description", "Prompt or text content"))
                        )),
                toolDef("list_public_tools",
                        "List all public browser-based developer tools hosted on WindBlog.",
                        Map.of("type", "object", "properties", Map.of()))
        );
    }

    private static Map<String, Object> toolDef(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", name);
        def.put("description", description);
        def.put("inputSchema", inputSchema);
        return def;
    }

    private Response rpcResult(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return Response.ok(resp).header("Cache-Control", "no-store").build();
    }

    private Response rpcError(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", Map.of("code", code, "message", message));
        return Response.ok(resp).header("Cache-Control", "no-store").build();
    }

    private static final class UnknownMcpToolException extends RuntimeException {
        UnknownMcpToolException(String message) {
            super(message);
        }
    }
}
