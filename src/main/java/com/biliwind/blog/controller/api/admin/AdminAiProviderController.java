package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiProviderConfigDto;
import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiProviderConfigUpdateRequest;
import com.biliwind.blog.service.ai.AiProviderConfigService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.concurrent.CompletionStage;

@Path("/api/admin/ai/providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAI")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminAiProviderController {

    @Inject
    AiProviderConfigService configService;

    @Inject
    com.biliwind.blog.service.AuditService auditService;

    @Inject
    com.biliwind.blog.service.ai.AiManager aiManager;

    @GET
    @Blocking
    public List<AiProviderConfigDto> list() {
        return configService.listAll().stream()
                .map(AiProviderConfigDto::of)
                .toList();
    }

    @POST
    @Blocking
    public Response create(AiProviderConfigUpdateRequest request) {
        if (request == null) throw new BadRequestException("内容不能为空");
        AiProviderConfigDto dto = AiProviderConfigDto.of(
                configService.upsert(null, new AiProviderConfigService.ConfigUpdate(
                        request.type(), request.name(), request.provider(), request.enabled(),
                        request.endpoint(), request.apiKey(), request.model(), request.config()
                ))
        );
        auditService.log("ai_provider", dto.id(), "create", null, java.util.Map.of("name", dto.name()));
        return Response.ok(dto).build();
    }

    @PUT
    @Path("/{id}")
    @Blocking
    public Response update(@PathParam("id") Long id, AiProviderConfigUpdateRequest request) {
        if (request == null) throw new BadRequestException("更新内容不能为 null");
        AiProviderConfigDto dto = AiProviderConfigDto.of(
                configService.upsert(id, new AiProviderConfigService.ConfigUpdate(
                        request.type(), request.name(), request.provider(), request.enabled(),
                        request.endpoint(), request.apiKey(), request.model(), request.config()
                ))
        );
        auditService.log("ai_provider", id, "update", null, java.util.Map.of("name", dto.name()));
        return Response.ok(dto).build();
    }

    @DELETE
    @Path("/{id}")
    @Blocking
    public Response delete(@PathParam("id") Long id) {
        configService.delete(id);
        auditService.log("ai_provider", id, "delete", null, null);
        return Response.noContent().build();
    }

    @POST
    @Path("/fetch-models")
    @Blocking
    public CompletionStage<List<String>> fetchModels(AiProviderConfigUpdateRequest request) {
        if (request == null) throw new BadRequestException("内容不能为空");
        com.biliwind.blog.model.AiProviderConfig config = new com.biliwind.blog.model.AiProviderConfig();
        config.type = request.type() != null ? request.type() : com.biliwind.blog.model.AiConfigType.PROVIDER;
        config.name = "temp-fetch-" + System.currentTimeMillis();
        config.enabled = true;
        config.provider = request.provider();
        config.endpoint = request.endpoint();
        config.apiKey = request.apiKey();
        return aiManager.fetchModels(config);
    }
}
