package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.service.ai.CodexCreatorHttpClient;
import com.biliwind.blog.service.ai.CodexCreatorConnectionService;
import com.biliwind.blog.service.ai.CodexCreatorDraftService;
import com.biliwind.blog.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

@Path("/api/admin/codex-creator")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminCodexCreator")
@SecurityRequirement(name = "adminBearerAuth")
@Blocking
public class AdminCodexCreatorController {
    @Inject
    CodexCreatorHttpClient client;

    @Inject
    CodexCreatorConnectionService connectionService;

    @Inject
    AuditService auditService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    CodexCreatorDraftService draftService;

    @GET
    @Path("/status")
    @Operation(summary = "查询 Codex Creator 内部连接状态")
    @Blocking
    public CompletionStage<Response> status() {
        return client.status()
                .thenApply(status -> Response.ok(Map.of("connected", true, "status", status)).build())
                .exceptionally(error -> Response.ok(Map.of("connected", false,
                        "error", rootMessage(error))).build());
    }

    @GET
    @Path("/config")
    @Operation(summary = "查询 Codex Creator 连接配置（密钥仅返回配置状态）")
    @Blocking
    public Response config() {
        return Response.ok(Map.of("success", true, "data", configView(connectionService.current()))).build();
    }

    @PUT
    @Path("/config")
    @Operation(summary = "更新 WindBlog 到 Codex Creator 的连接配置")
    @Blocking
    public Response updateConfig(Map<String, Object> payload) {
        if (payload == null) {
            return badRequest("配置内容不能为空");
        }

        String endpoint = value(payload.get("endpoint"));
        String sharedSecret = payload.containsKey("sharedSecret")
                ? value(payload.get("sharedSecret")) : null;
        String model = payload.containsKey("model") ? value(payload.get("model")) : null;

        try {
            Boolean enabled = booleanValue(payload.get("enabled"));
            boolean clearSharedSecret = Boolean.TRUE.equals(booleanValue(payload.get("clearSharedSecret")));
            CodexCreatorConnectionService.ConnectionSettings saved = connectionService.save(
                    endpoint, sharedSecret, enabled, model, clearSharedSecret);
            auditService.log("codex_creator", saved.providerId(), "update_connection", null,
                    Map.of("endpoint", saved.endpoint(), "enabled", saved.enabled(),
                            "sharedSecretConfigured", saved.sharedSecretConfigured()));
            return Response.ok(Map.of("success", true, "data", configView(saved))).build();
        } catch (IllegalArgumentException exception) {
            return badRequest(exception.getMessage());
        }
    }

    @GET
    @Path("/topic-automation")
    @Operation(summary = "查询 AI 主题自动化设置和主题池")
    public CompletionStage<Response> topicAutomation() {
        CompletionStage<JsonNode> settings = client.topicCommand("settings.read", Map.of(), actorId(), traceId());
        CompletionStage<JsonNode> seeds = client.topicCommand("seeds.list", Map.of(), actorId(), traceId());
        return settings.thenCombine(seeds, (settingsResponse, seedResponse) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("settings", commandData(settingsResponse));
            JsonNode seedData = commandData(seedResponse);
            data.put("seeds", seedData.path("items"));
            return Response.ok(Map.of("success", true, "data", data)).build();
        }).exceptionally(error -> serviceUnavailable(error));
    }

    @PUT
    @Path("/topic-automation")
    @Operation(summary = "更新 AI 主题自动化设置")
    public CompletionStage<Response> updateTopicAutomation(Map<String, Object> payload) {
        return proxy("settings.update", payload == null ? Map.of() : payload);
    }

    @POST
    @Path("/topic-seeds")
    @Operation(summary = "创建 AI 主题查询种子")
    public CompletionStage<Response> createTopicSeed(Map<String, Object> payload) {
        return proxy("seeds.upsert", payload == null ? Map.of() : payload);
    }

    @PUT
    @Path("/topic-seeds/{id}")
    @Operation(summary = "更新 AI 主题查询种子")
    public CompletionStage<Response> updateTopicSeed(@PathParam("id") Long id, Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        request.put("id", id);
        return proxy("seeds.upsert", request);
    }

    @DELETE
    @Path("/topic-seeds/{id}")
    @Operation(summary = "删除 AI 主题查询种子")
    public CompletionStage<Response> deleteTopicSeed(@PathParam("id") Long id) {
        return proxy("seeds.delete", Map.of("id", id));
    }

    @POST
    @Path("/topic-runs")
    @Operation(summary = "立即执行一次 AI 主题发现")
    public CompletionStage<Response> startTopicRun(
            Map<String, Object> payload,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        Map<String, Object> request = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        request.putIfAbsent("idempotencyKey",
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? UUID.randomUUID().toString() : idempotencyKey.trim());
        return proxy("runs.start", request);
    }

    @GET
    @Path("/models")
    @Operation(summary = "查询话题与文章生成可选模型")
    public CompletionStage<Response> models() {
        return proxy("models.list", Map.of());
    }

    @GET @Path("/test-servers")
    @Operation(summary = "查询 AI 测试服务器")
    public CompletionStage<Response> testServers() { return proxy("test-servers.list", Map.of()); }

    @GET @Path("/test-servers/setup-guide")
    @Operation(summary = "查询 AI 测试服务器接入说明")
    public CompletionStage<Response> testServerSetupGuide() { return proxy("test-servers.setup-guide", Map.of()); }

    @POST @Path("/test-servers")
    public CompletionStage<Response> createTestServer(Map<String,Object> payload) { return proxy("test-servers.create", payload == null ? Map.of() : payload); }

    @PUT @Path("/test-servers/{id}")
    public CompletionStage<Response> updateTestServer(@PathParam("id") Long id, Map<String,Object> payload) { Map<String,Object> p=new LinkedHashMap<>(payload == null ? Map.of() : payload); p.put("id",id); return proxy("test-servers.update",p); }

    @DELETE @Path("/test-servers/{id}")
    public CompletionStage<Response> deleteTestServer(@PathParam("id") Long id) { return proxy("test-servers.delete", Map.of("id",id)); }

    @GET
    @Path("/topic-runs/{id}")
    @Operation(summary = "查询 AI 主题发现运行")
    public CompletionStage<Response> topicRun(@PathParam("id") Long id) {
        return proxy("runs.read", Map.of("id", id));
    }

    @GET
    @Path("/topic-runs")
    @Operation(summary = "分页查询 AI 主题发现运行")
    public CompletionStage<Response> topicRuns(@QueryParam("page") @DefaultValue("1") int page,
                                                @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        return proxy("runs.list", Map.of("page", page, "pageSize", pageSize));
    }

    @GET
    @Path("/topics")
    @Operation(summary = "分页查询 AI 主题")
    public CompletionStage<Response> topics(@QueryParam("status") String status,
                                            @QueryParam("page") @DefaultValue("1") int page,
                                            @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (status != null) request.put("status", status);
        request.put("page", page);
        request.put("pageSize", pageSize);
        return proxy("topics.list", request);
    }

    @GET
    @Path("/topics/{id}")
    @Operation(summary = "查询 AI 主题详情")
    public CompletionStage<Response> topic(@PathParam("id") Long id) {
        return proxy("topics.read", Map.of("id", id));
    }

    @POST
    @Path("/topics/{id}/review")
    @Operation(summary = "审核 AI 主题")
    public CompletionStage<Response> reviewTopic(@PathParam("id") Long id, Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        request.put("id", id);
        return proxy("topics.review", request);
    }

    @POST
    @Path("/topics/{id}/draft")
    @Operation(summary = "为 AI 主题生成 WindBlog 草稿")
    public CompletionStage<Response> createDraft(@PathParam("id") Long id, Map<String, Object> payload) {
        String language = payload == null ? null : value(payload.get("language"));
        String instructions = payload == null ? null : value(payload.get("instructions"));
        String profileId = payload == null ? null : value(payload.get("profileId"));
        String reasoningEffort = payload == null ? null : value(payload.get("reasoningEffort"));
        Long categoryId = longValue(payload == null ? null : payload.get("categoryId"));
        boolean requiresPracticalVerification = payload != null && Boolean.TRUE.equals(booleanValue(payload.get("requiresPracticalVerification")));
        java.util.List<Long> testServerIds = payload == null ? java.util.List.of() : longList(payload.get("testServerIds"));
        try {
            return draftService.start(id, categoryId, language, instructions, profileId, reasoningEffort, requiresPracticalVerification, testServerIds,
                            adminRequestContext.getUserId(), traceId())
                    .thenApply(data -> Response.accepted(Map.of("success", true, "data", data)).build())
                    .exceptionally(error -> serviceUnavailable(error));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(badRequest(exception.getMessage()));
        }
    }

    @POST
    @Path("/topics/{id}/draft/regenerate")
    @Operation(summary = "重新生成已指派主题的 WindBlog 草稿")
    public CompletionStage<Response> regenerateDraft(@PathParam("id") Long id, Map<String, Object> payload) {
        String profileId = payload == null ? null : value(payload.get("profileId"));
        String reasoningEffort = payload == null ? null : value(payload.get("reasoningEffort"));
        try {
            return draftService.regenerate(id, profileId, reasoningEffort, adminRequestContext.getUserId(), traceId())
                    .thenApply(data -> Response.accepted(Map.of("success", true, "data", data)).build())
                    .exceptionally(error -> serviceUnavailable(error));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(badRequest(exception.getMessage()));
        }
    }

    @GET
    @Path("/draft-jobs/{id}")
    @Operation(summary = "查询 AI 草稿任务")
    public CompletionStage<Response> draftJob(@PathParam("id") Long id) {
        try {
            return draftService.refresh(id, adminRequestContext.getUserId(), traceId())
                    .thenApply(data -> Response.ok(Map.of("success", true, "data", data)).build())
                    .exceptionally(error -> serviceUnavailable(error));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(badRequest(exception.getMessage()));
        }
    }

    private CompletionStage<Response> proxy(String command, Object payload) {
        return client.topicCommand(command, payload, actorId(), traceId())
                .thenApply(response -> Response.ok(response).build())
                .exceptionally(error -> serviceUnavailable(error));
    }

    private JsonNode commandData(JsonNode response) {
        if (response == null || !response.path("success").asBoolean(false)) {
            throw new IllegalStateException(response == null ? "Codex Creator 无响应"
                    : response.path("message").asText("Codex Creator 请求失败"));
        }
        return response.path("data");
    }

    private Response serviceUnavailable(Throwable error) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("success", false, "message", rootMessage(error))).build();
    }

    private Map<String, Object> configView(CodexCreatorConnectionService.ConnectionSettings config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("providerId", config.providerId());
        view.put("providerName", config.providerName());
        view.put("endpoint", config.endpoint());
        view.put("enabled", config.enabled());
        view.put("sharedSecretConfigured", config.sharedSecretConfigured());
        view.put("hasDatabaseConfig", config.databaseConfigured());
        view.put("source", config.databaseConfigured() ? "database" : "environment");
        view.put("model", config.model() == null ? "" : config.model());
        view.put("updatedAt", config.updatedAt() == null ? null : config.updatedAt());
        return view;
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("success", false, "message", message == null ? "配置无效" : message))
                .build();
    }

    private static String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static java.util.List<Long> longList(Object value) {
        if (!(value instanceof java.util.List<?> values)) return java.util.List.of();
        return values.stream().map(AdminCodexCreatorController::longValue).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private String actorId() {
        Long userId = adminRequestContext.getUserId();
        return "windblog-admin:" + (userId == null ? "unknown" : userId);
    }

    private String traceId() {
        return UUID.randomUUID().toString();
    }

    private static Boolean booleanValue(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean booleanValue) return booleanValue;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        throw new IllegalArgumentException("布尔配置项必须是 true 或 false");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
