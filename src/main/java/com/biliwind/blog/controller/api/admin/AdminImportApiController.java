package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportProgressEvent;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportResult;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.TestConnectionRequest;
import com.biliwind.blog.service.ImportService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.function.Function;

@Path("/api/admin/import")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminImport")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminImportApiController {

    @Inject
    ImportService importService;

    @Inject
    AdminRequestContext adminRequestContext;

    @POST
    @Path("/test-connection")
    @Operation(summary = "测试数据库连接")
    public java.util.Map<String, Object> testConnection(TestConnectionRequest req) {
        boolean success = importService.testConnection(req.driver(), req.url(), req.username(), req.password());
        return java.util.Map.of("success", success, "message", success ? "连接成功" : "连接失败，请检查配置");
    }

    @POST
    @Blocking
    @Operation(summary = "执行数据导入")
    public ImportResult doImport(ImportRequest req) {
        if (!adminRequestContext.isSuperAdmin()) {
            throw new ForbiddenException("只有超级管理员可以执行导入操作");
        }
        Long operatorId = adminRequestContext.getUserId();
        return importService.doImport(req, operatorId);
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(summary = "获取导入进度 SSE 流")
    public Multi<ImportProgressEvent> stream() {
        Multi<ImportProgressEvent> events = importService.getEventStream();
        // 每 15 秒发送一个心跳包，防止网络连接超时断开
        Multi<ImportProgressEvent> ticks = Multi.createFrom().ticks().every(Duration.ofSeconds(15))
                .map(new Function<Long, ImportProgressEvent>() {
                    @Override
                    public ImportProgressEvent apply(Long tick) {
                        return new ImportProgressEvent("ping", "keep-alive", null);
                    }
                });
        return Multi.createBy().merging().streams(events, ticks);
    }
}
