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

@Path("/api/admin/ai/providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminAI")
@SecurityRequirement(name = "adminBearerAuth")
@Blocking
public class AdminAiProviderController {

    @Inject
    AiProviderConfigService configService;

    @Inject
    com.biliwind.blog.service.AuditService auditService;

    @GET
    public List<AiProviderConfigDto> list() {
        return configService.listAll().stream()
                .map(AiProviderConfigDto::of)
                .toList();
    }

    @POST
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
    public Response delete(@PathParam("id") Long id) {
        configService.delete(id);
        auditService.log("ai_provider", id, "delete", null, null);
        return Response.noContent().build();
    }
}
