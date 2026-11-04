package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.service.AiArticleDraftService;
import com.biliwind.blog.service.ai.AiArticleDraftRunService;
import com.biliwind.blog.service.ai.LocalAiTopicService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI topic pool, discovery runs, seeds and article drafting. All generation is
 * served by the configured AI providers; there is no external Codex Creator
 * dependency.
 */
@Path("/api/admin/ai/topics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAI")
@SecurityRequirement(name = "adminBearerAuth")
@Blocking
public class AdminAiTopicController {

    @Inject
    LocalAiTopicService topicService;

    @Inject
    AiArticleDraftService draftService;

    @Inject
    AiArticleDraftRunService draftRunService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    ObjectMapper objectMapper;

    @GET
    @Operation(summary = "分页查询 AI 话题")
    public Response topics(@QueryParam("status") String status,
                           @QueryParam("page") @DefaultValue("1") int page,
                           @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (status != null) request.put("status", status);
        request.put("page", page);
        request.put("pageSize", pageSize);
        return ok(dispatch("topics.list", request));
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "查询 AI 话题详情")
    public Response topic(@PathParam("id") Long id) {
        return ok(dispatch("topics.read", Map.of("id", id)));
    }

    @POST
    @Path("/{id}/review")
    @Operation(summary = "审核 AI 话题")
    public Response review(@PathParam("id") Long id, Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        request.put("id", id);
        return ok(dispatch("topics.review", request));
    }

    @GET
    @Path("/automation")
    @Operation(summary = "查询话题自动化设置与查询种子")
    public Response automation() {
        Map<String, Object> settings = dispatch("settings.read", Map.of());
        Map<String, Object> seeds = dispatch("seeds.list", Map.of());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settings", settings);
        data.put("seeds", seeds.getOrDefault("items", java.util.List.of()));
        return ok(data);
    }

    @PUT
    @Path("/automation")
    @Operation(summary = "更新话题自动化设置")
    public Response updateAutomation(Map<String, Object> payload) {
        return ok(dispatch("settings.update", payload == null ? Map.of() : payload));
    }

    @GET
    @Path("/seeds")
    @Operation(summary = "查询话题查询种子")
    public Response seeds() {
        return ok(dispatch("seeds.list", Map.of()));
    }

    @POST
    @Path("/seeds")
    @Operation(summary = "创建话题查询种子")
    public Response createSeed(Map<String, Object> payload) {
        return ok(dispatch("seeds.upsert", payload == null ? Map.of() : payload));
    }

    @PUT
    @Path("/seeds/{id}")
    @Operation(summary = "更新话题查询种子")
    public Response updateSeed(@PathParam("id") Long id, Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        request.put("id", id);
        return ok(dispatch("seeds.upsert", request));
    }

    @DELETE
    @Path("/seeds/{id}")
    @Operation(summary = "删除话题查询种子")
    public Response deleteSeed(@PathParam("id") Long id) {
        return ok(dispatch("seeds.delete", Map.of("id", id)));
    }

    @POST
    @Path("/runs")
    @Operation(summary = "立即执行一次 AI 话题发现")
    public Response startRun(Map<String, Object> payload) {
        return ok(dispatch("runs.start", payload == null ? Map.of() : payload));
    }

    @GET
    @Path("/runs")
    @Operation(summary = "分页查询话题发现运行")
    public Response runs(@QueryParam("page") @DefaultValue("1") int page,
                         @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        return ok(dispatch("runs.list", Map.of("page", page, "pageSize", pageSize)));
    }

    @GET
    @Path("/runs/{id}")
    @Operation(summary = "查询话题发现运行")
    public Response run(@PathParam("id") Long id) {
        return ok(dispatch("runs.read", Map.of("id", id)));
    }

    @POST
    @Path("/{id}/draft")
    @Operation(summary = "为话题异步生成文章草稿")
    public Response startDraft(@PathParam("id") Long id, Map<String, Object> payload) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        Map<String, Object> job = draftService.start(id,
                longValue(body.get("providerId")),
                text(body.get("language")),
                text(body.get("instructions")),
                text(body.get("repostPolicyCode")),
                false);
        return Response.accepted(Map.of("success", true, "data", job)).build();
    }

    @POST
    @Path("/{id}/draft/regenerate")
    @Operation(summary = "重新生成话题文章草稿")
    public Response regenerateDraft(@PathParam("id") Long id, Map<String, Object> payload) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        Map<String, Object> job = draftService.start(id,
                longValue(body.get("providerId")),
                text(body.get("language")),
                text(body.get("instructions")),
                text(body.get("repostPolicyCode")),
                true);
        return Response.accepted(Map.of("success", true, "data", job)).build();
    }

    @GET
    @Path("/draft-jobs/{id}")
    @Operation(summary = "查询 AI 草稿任务")
    public Response draftJob(@PathParam("id") Long id) {
        return ok(draftService.job(id));
    }

    private Map<String, Object> dispatch(String command, Map<String, Object> payload) {
        Object result = topicService.dispatch(command, objectMapper.valueToTree(payload),
                "windblog-admin:" + actorId(), UUID.randomUUID().toString());
        if (result instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private Response ok(Object data) {
        return Response.ok(Map.of("success", true, "data", data)).build();
    }

    private Long actorId() {
        Long userId = adminRequestContext.getUserId();
        return userId == null ? 0L : userId;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? null : Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
