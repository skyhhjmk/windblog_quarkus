package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiProviderConfigDto;
import com.biliwind.blog.controller.api.admin.dto.AdminAiProviderDtos.AiProviderConfigUpdateRequest;
import com.biliwind.blog.service.ai.AiProviderConfigService;
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
public class AdminAiProviderController {

    @Inject
    AiProviderConfigService configService;

    @GET
    public List<AiProviderConfigDto> list() {
        return configService.listAll().stream()
                .map(AiProviderConfigDto::of)
                .toList();
    }

    @PUT
    @Path("/{provider}")
    public Response update(@PathParam("provider") String provider,
                           AiProviderConfigUpdateRequest request) {
        if (request == null) {
            throw new BadRequestException("更新内容不能为 null");
        }

        AiProviderConfigDto dto = AiProviderConfigDto.of(
                configService.upsert(provider, new AiProviderConfigService.ConfigUpdate(
                        request.enabled(),
                        request.endpoint(),
                        request.apiKey(),
                        request.model()
                ))
        );
        return Response.ok(dto).build();
    }
}
