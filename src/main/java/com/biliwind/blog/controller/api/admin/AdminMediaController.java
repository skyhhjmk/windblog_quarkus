package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminMediaDtos;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.MediaManagementService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Path("/api/admin/media")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminMedia")
public class AdminMediaController {

    @Inject
    MediaManagementService mediaService;
    @Inject
    AdminTokenVerifier tokenVerifier;

    @GET
    @Operation(summary = "列出媒体资源")
    public AdminMediaDtos.MediaListResult list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("unreferenced") @DefaultValue("false") boolean unreferenced) {
        return mediaService.listMedia(page, pageSize, unreferenced);
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    @Operation(summary = "上传媒体文件")
    public AdminMediaDtos.MediaItem upload(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                           MultipartFormDataInput input) {
        User operator = mustFindOperator(authorization);
        InputPart filePart = extractFilePart(input);
        String fileName = extractFileName(filePart);
        String mimeType = filePart.getMediaType() != null ? filePart.getMediaType().toString() : null;
        long declaredSize = extractContentLength(filePart);
        try (InputStream stream = filePart.getBody(InputStream.class, null)) {
            Media media = mediaService.storeUploadedMedia(operator, stream, fileName, mimeType, declaredSize);
            return mediaService.toDto(media, List.of());
        } catch (IOException e) {
            throw new BadRequestException("无法读取上传内容");
        }
    }

    @POST
    @Path("/scan")
    @Transactional
    @Operation(summary = "重建媒体引用索引")
    public AdminMediaDtos.MediaScanResult scan(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
        mustFindOperator(authorization);
        return mediaService.rebuildReferences();
    }

    private InputPart extractFilePart(MultipartFormDataInput input) {
        Map<String, List<InputPart>> parts = input.getFormDataMap();
        List<InputPart> files = parts.get("file");
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("缺少 file 字段");
        }
        return files.get(0);
    }

    private String extractFileName(InputPart part) {
        String header = part.getHeaders().getFirst("Content-Disposition");
        if (header == null) {
            return "file";
        }
        for (String item : header.split(";")) {
            String trimmed = item.trim();
            if (trimmed.startsWith("filename=")) {
                String name = trimmed.substring("filename=".length()).trim();
                if (name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                return name;
            }
        }
        return "file";
    }

    private long extractContentLength(InputPart part) {
        String header = part.getHeaders().getFirst("Content-Length");
        if (header == null) {
            return -1;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private User mustFindOperator(String authorization) {
        Long userId = extractUserId(authorization);
        if (userId == null) {
            throw unauthorized();
        }
        User user = User.find("id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
        if (user == null) {
            throw unauthorized();
        }
        return user;
    }

    private Long extractUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            return null;
        }
        AdminTokenVerifier.VerifiedToken verified = tokenVerifier.verify(token);
        if (verified == null || !verified.isAdmin()) {
            return null;
        }
        return verified.uid();
    }

    private WebApplicationException unauthorized() {
        return new WebApplicationException(Response.status(Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("success", false, "message", "需要登录后操作"))
                .build());
    }
}
