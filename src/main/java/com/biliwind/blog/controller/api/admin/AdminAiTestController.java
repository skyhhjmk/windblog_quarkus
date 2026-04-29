package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.service.ai.AiProviderConfigService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/api/admin/ai/test")
@Produces(MediaType.SERVER_SENT_EVENTS)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAI")
@SecurityRequirement(name = "adminBearerAuth")
@Blocking
public class AdminAiTestController {

    @Inject
    AiProviderConfigService configService;

    @Inject
    com.biliwind.blog.service.ai.AiManager aiManager;

    @Inject
    com.biliwind.blog.service.AuditService auditService;

    @Inject
    com.biliwind.blog.context.AdminRequestContext adminRequestContext;

    @POST
    @Path("/{id}")
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> testStream(@PathParam("id") Long configId, com.biliwind.blog.controller.api.admin.dto.AiTestRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return Multi.createFrom().failure(new BadRequestException("Prompt cannot be empty"));
        }

        AiProviderConfig config = configService.getById(configId).orElse(null);
        if (config == null) {
            return Multi.createFrom().failure(new NotFoundException("AI Configuration not found"));
        }

        long startTime = System.currentTimeMillis();
        // 捕获操作人
        Long performingUserId = adminRequestContext.getUserId();

        // 记录测试触发的起始审计日志（初步记录）
        auditService.log("ai_provider", configId, "ai_provider_test_triggered",
                null, java.util.Map.of("prompt", request.prompt(), "provider", config.provider));

        // 用于捕获流式输出内容以记录到审计日志
        StringBuilder outputBuffer = new StringBuilder();

        // 调用流式接口
        return aiManager.testStream(config, request).onItem().invoke(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String chunk) {
                if (chunk != null) {
                    outputBuffer.append(chunk);
                }
            }
        }).onTermination().invoke(new Runnable() {
            @Override
            public void run() {
                long durationMs = System.currentTimeMillis() - startTime;
                String finalOutput = outputBuffer.toString();

                // 流式任务完成后的补充记录
                auditService.log("ai_provider", configId, "ai_provider_test_completed",
                        null, java.util.Map.of("output", finalOutput), durationMs, null, null, null);
            }
        });
    }
}
