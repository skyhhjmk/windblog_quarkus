package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.service.ai.AiManager;
import com.biliwind.blog.service.ai.AiResult;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

import io.smallrye.mutiny.Multi;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * Completion endpoint used by the Flutter writing assistant.
 * Supports both streaming (SSE) to prevent public edge timeouts (HTTP 554)
 * and non-streaming fallback. Provider selection goes through {@link AiManager},
 * so polling groups and fallbacks are supported automatically.
 */
@Path("/api/admin/ai/complete")
@Tag(name = "AdminAI")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminAiCompletionController {

    @Inject
    public AiManager aiManager;

    @POST
    @Path("/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    @Operation(summary = "使用指定或默认 AI 提供商流式完成写作任务")
    public Multi<String> completeStreamPath(CompletionRequest request) {
        return handleStream(request);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    @Operation(summary = "使用指定或默认 AI 提供商流式完成写作任务")
    public Multi<String> completeStream(CompletionRequest request) {
        return handleStream(request);
    }

    private Multi<String> handleStream(CompletionRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return Multi.createFrom().failure(new BadRequestException("prompt 不能为空"));
        }
        Multi<String> stream = aiManager.completeStream(request.providerId(), request.systemPrompt(),
                request.prompt());
        Multi<String> start = Multi.createFrom().item("{\"type\":\"start\"}");
        return Multi.createBy().concatenating().streams(start, stream);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    @Operation(summary = "使用指定或默认 AI 提供商完成写作任务")
    public Response complete(CompletionRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new BadRequestException("prompt 不能为空");
        }
        try {
            AiResult result = aiManager.complete(request.providerId(), request.systemPrompt(),
                            request.prompt())
                    .toCompletableFuture()
                    .join();
            String text = result.contents == null ? "" : result.contents.get("text");
            if (text == null || text.isBlank()) {
                throw new BadRequestException("AI 提供商未返回写作结果");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("text", text);
            body.put("provider", result.provider);
            body.put("modelId", result.modelId);
            body.put("inputTokens", result.inputTokens);
            body.put("outputTokens", result.outputTokens);
            body.put("totalTokens", result.totalTokens);
            return Response.ok(body).build();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BadRequestException("AI 写作失败: " + safeMessage(cause));
        }
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    public record CompletionRequest(Long providerId, String systemPrompt, String prompt) {
    }
}
