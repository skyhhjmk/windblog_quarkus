package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AiTestRequest;
import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.service.ai.AiManager;
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
    AiManager aiManager;

    @POST
    @Path("/{id}")
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> testStream(@PathParam("id") Long configId, AiTestRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return Multi.createFrom().failure(new BadRequestException("Prompt cannot be empty"));
        }

        AiProviderConfig config = configService.getById(configId).orElse(null);
        if (config == null) {
            return Multi.createFrom().failure(new NotFoundException("AI Configuration not found"));
        }

        return aiManager.testStream(config, request);
    }
}
