package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.controller.api.admin.dto.AdminMediaDtos;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.MediaManagementService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
    com.biliwind.blog.context.AdminRequestContext adminRequestContext;

    @Inject
    jakarta.enterprise.event.Event<com.biliwind.blog.service.edge.DataSyncEvent> dataSyncEvent;

    @Inject
    com.biliwind.blog.service.RegionValidationService regionValidationService;

    @GET
    @Operation(summary = "列出媒体资源")
    public AdminMediaDtos.MediaListResult list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("unreferenced") @DefaultValue("false") boolean unreferenced,
            @QueryParam("failedOnly") @DefaultValue("false") boolean failedOnly) {
        return mediaService.listMedia(page, pageSize, unreferenced, failedOnly);
    }

    @GET
    @Path("/find")
    @Operation(summary = "根据 URL 查找媒体详情")
    public AdminMediaDtos.MediaItem find(@QueryParam("url") String url) {
        mustFindOperator();
        Media media = mediaService.findByUrl(url);
        if (media == null) {
            throw new NotFoundException("未找到匹配的媒体资源: " + url);
        }
        // 加载引用列表
        List<com.biliwind.blog.model.PostMedia> references = com.biliwind.blog.model.PostMedia.find("media.id = ?1", media.id).list();
        return mediaService.toDto(media, references);
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    @Operation(summary = "上传媒体文件")
    public AdminMediaDtos.MediaItem upload(@RestForm("file") FileUpload filePart) {
        User operator = mustFindOperator();
        if (filePart == null) {
            throw new BadRequestException("缺少 file 字段");
        }
        String fileName = filePart.fileName();
        String mimeType = filePart.contentType();
        long declaredSize = filePart.size();
        try (InputStream stream = Files.newInputStream(filePart.filePath())) {
            Media media = mediaService.storeUploadedMedia(operator, stream, fileName, mimeType, declaredSize);
            dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("MEDIA", media.id, "UPSERT"));
            return mediaService.toDto(media, List.of());
        } catch (IOException e) {
            throw new BadRequestException("无法读取上传内容");
        }
    }

    @POST
    @Path("/scan")
    @Transactional
    @Operation(summary = "重建媒体引用索引")
    public AdminMediaDtos.MediaScanResult scan() {
        mustFindOperator();
        return mediaService.rebuildReferences();
    }

    @POST
    @Path("/{id}/retry")
    @Transactional
    @Operation(summary = "重试导入失败的媒体")
    public AdminMediaDtos.MediaItem retry(@PathParam("id") Long id) {
        mustFindOperator();
        try {
            Media media = mediaService.retryImport(id);
            return mediaService.toDto(media, List.of());
        } catch (IOException e) {
            throw new BadRequestException("重试失败: " + e.getMessage());
        }
    }

    @POST
    @Path("/batch-retry")
    @Transactional
    @Operation(summary = "批量重试导入失败的媒体")
    public AdminMediaDtos.BatchRetryResult batchRetry() {
        mustFindOperator();
        return mediaService.batchRetryFailedImports();
    }

    @PATCH
    @Path("/{id}")
    @Transactional
    @Operation(summary = "更新媒体设置")
    public AdminMediaDtos.MediaItem update(@PathParam("id") Long id, AdminMediaDtos.MediaUpdateRequest request) {
        mustFindOperator();
        Media media = Media.findById(id);
        if (media == null) {
            throw new NotFoundException("媒体不存在");
        }
        if (request.visibilityRegions() != null) {
            media.visibilityRegions = regionValidationService.validateAndFilterRegions(request.visibilityRegions());
        }
        media.persist();
        dataSyncEvent.fire(new com.biliwind.blog.service.edge.DataSyncEvent("MEDIA", media.id, "UPSERT"));
        return mediaService.toDto(media, List.of());
    }


    private User mustFindOperator() {
        Long userId = adminRequestContext.getUserId();
        if (userId == null) {
            throw unauthorized();
        }
        User user = User.find("id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
        if (user == null) {
            throw unauthorized();
        }
        return user;
    }

    private WebApplicationException unauthorized() {
        return new WebApplicationException(Response.status(Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("success", false, "message", "需要登录后操作"))
                .build());
    }
}
