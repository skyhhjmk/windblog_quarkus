package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.service.ai.AiTopicDiscoveryService;
import com.biliwind.blog.service.ai.AiTopicModeService;
import com.biliwind.blog.service.ai.TopicAutomationGateway;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous API-provider topic discovery. The admin UI normally uses the
 * asynchronous {@code runs.start} command so slow providers cannot hit the
 * public edge timeout; this endpoint remains for direct/integration callers.
 */
@Path("/api/admin/ai/topics/discover")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAI")
@SecurityRequirement(name = "adminBearerAuth")
@Blocking
public class AdminAiTopicDiscoveryController {

    @Inject
    AiTopicDiscoveryService discoveryService;

    @Inject
    TopicAutomationGateway topicGateway;

    @Inject
    AiTopicModeService topicMode;

    @Inject
    com.biliwind.blog.context.AdminRequestContext adminRequestContext;

    @POST
    @Operation(summary = "使用 AI 提供商或轮询组同步生成技术文章话题")
    public Response discover(DiscoveryRequest request) {
        int maxTopics = request == null || request.maxTopics() == null
                ? 10 : Math.max(1, Math.min(50, request.maxTopics()));
        List<AiTopicDiscoveryService.Seed> seeds = request == null
                ? List.of() : AiTopicDiscoveryService.safeSeeds(request.seeds());
        if (seeds.isEmpty()) {
            seeds = loadSeeds();
        }
        if (seeds.isEmpty()) {
            throw new BadRequestException("话题种子为空，请先配置主题查询种子");
        }
        String language = request == null || request.language() == null || request.language().isBlank()
                ? "zh-CN" : request.language().trim();

        Long providerId = request == null ? null : request.providerId();
        if (topicMode.localMode() && topicMode.isCodexProvider(providerId)) {
            // A Codex Creator provider cannot generate without the Codex service;
            // fall back to the provider pinned to the topic operation.
            providerId = null;
        }
        List<Map<String, Object>> topics = discoveryService.discover(
                providerId, seeds, maxTopics, language);

        try {
            JsonNode imported = topicGateway.command("topics.import",
                            Map.of("topics", topics),
                            "windblog-admin:" + actorId(), UUID.randomUUID().toString())
                    .toCompletableFuture()
                    .join();
            JsonNode data = imported.path("data");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("created", data.path("created").asInt(0));
            body.put("updated", data.path("updated").asInt(0));
            body.put("total", data.path("total").asInt(topics.size()));
            body.put("topics", topics);
            return Response.ok(body).build();
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return Response.status(topicMode.localMode()
                            ? Response.Status.INTERNAL_SERVER_ERROR
                            : Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("success", false,
                            "message", "话题已生成但保存失败: " + safeMessage(cause),
                            "topics", topics))
                    .build();
        }
    }

    private List<AiTopicDiscoveryService.Seed> loadSeeds() {
        try {
            JsonNode response = topicGateway.command("seeds.list", Map.of(),
                            "windblog-admin:" + actorId(), UUID.randomUUID().toString())
                    .toCompletableFuture().join();
            JsonNode items = response.path("data").path("items");
            List<AiTopicDiscoveryService.Seed> seeds = new ArrayList<>();
            for (JsonNode item : items) {
                if (!item.path("enabled").asBoolean(true)) continue;
                long id = item.path("id").asLong(0);
                if (id <= 0) continue;
                seeds.add(new AiTopicDiscoveryService.Seed(id,
                        item.path("name").asText(""), item.path("query").asText(""),
                        item.path("language").asText("zh-CN")));
            }
            return seeds;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private Long actorId() {
        Long userId = adminRequestContext.getUserId();
        return userId == null ? 0L : userId;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) return "未知错误";
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    public record DiscoveryRequest(Long providerId, Integer maxTopics, String language,
                                   List<AiTopicDiscoveryService.Seed> seeds) {
    }
}
