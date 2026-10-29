package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.AiArticleDraftService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * API-provider article drafting for a topic in the shared Codex Creator pool.
 */
@Path("/api/admin/ai/topics/{id}/draft")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAI")
@SecurityRequirement(name = "adminBearerAuth")
@Blocking
public class AdminAiTopicDraftController {

    @Inject
    AiArticleDraftService draftService;

    @POST
    @Operation(summary = "使用 AI 提供商或轮询组为话题生成文章草稿")
    public AiArticleDraftService.DraftResult generate(@PathParam("id") Long id, DraftRequest request) {
        return draftService.generate(id,
                request == null ? null : request.providerId(),
                request == null ? null : request.language(),
                request == null ? null : request.instructions(),
                request == null ? null : request.repostPolicyCode());
    }

    public record DraftRequest(Long providerId, String language, String instructions,
                               String repostPolicyCode) {
    }
}
