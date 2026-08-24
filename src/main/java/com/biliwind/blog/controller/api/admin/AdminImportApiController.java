package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportProgressEvent;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportResult;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.TestConnectionRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.AnalyzeRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.AnalysisResponse;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ExecuteRequest;
import com.biliwind.blog.service.ImportAnalysisService;
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
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.time.Duration;

@Path("/api/admin/import")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminImport")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminImportApiController {

    @Inject
    ImportService importService;

    @Inject
    ImportAnalysisService importAnalysisService;

    @Inject
    AdminRequestContext adminRequestContext;

    @POST
    @Path("/analyze")
    @Blocking
    @Operation(summary = "预分析源数据库")
    public AnalysisResponse analyze(AnalyzeRequest req) {
        requireSuperAdmin();
        return importAnalysisService.analyzeDatabase(req);
    }

    @POST
    @Path("/analyze-sql")
    @Blocking
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "预分析 PostgreSQL SQL 文件")
    public AnalysisResponse analyzeSql(@RestForm("file") FileUpload file) {
        requireSuperAdmin();
        return importAnalysisService.analyzeSqlFile(file);
    }

    @GET
    @Path("/analysis/{id}")
    @Operation(summary = "获取导入预分析报告")
    public AnalysisResponse getAnalysis(@PathParam("id") String id) {
        requireSuperAdmin();
        ImportAnalysisService.Session session = importAnalysisService.requireSession(id);
        Object blockers = session.report().get("blockers");
        String status = blockers instanceof java.util.Collection<?> collection && !collection.isEmpty()
                ? "BLOCKED" : "READY";
        return new AnalysisResponse(session.id(), session.sourceType(), status, session.report(),
                session.expiresAt().toString());
    }

    @POST
    @Path("/test-connection")
    @Operation(summary = "测试数据库连接")
    public java.util.Map<String, Object> testConnection(TestConnectionRequest req) {
        requireSuperAdmin();
        boolean success = importService.testConnection(req.driver(), req.url(), req.username(), req.password());
        return java.util.Map.of("success", success, "message", success ? "连接成功" : "连接失败，请检查配置");
    }

    @POST
    @Blocking
    @Operation(summary = "执行数据导入")
    public ImportResult doImport(ImportRequest req) {
        requireSuperAdmin();
        Long operatorId = adminRequestContext.getUserId();
        return importService.doImport(req, operatorId);
    }

    @POST
    @Path("/execute")
    @Blocking
    @Operation(summary = "执行已分析的导入任务")
    public ImportResult execute(ExecuteRequest req) {
        requireSuperAdmin();
        if (req == null || req.analysisId() == null || req.analysisId().isBlank()) {
            throw new BadRequestException("缺少分析报告 ID");
        }
        ImportAnalysisService.Session session = importAnalysisService.requireSession(req.analysisId());
        if (req.sourceType() != null && !req.sourceType().isBlank()
                && !req.sourceType().equalsIgnoreCase(session.sourceType())) {
            throw new BadRequestException("分析报告来源类型与执行请求不一致，请重新分析");
        }
        Object blockers = session.report().get("blockers");
        if (blockers instanceof java.util.Collection<?> collection && !collection.isEmpty()) {
            throw new BadRequestException("预分析报告存在阻断项，请修复后重新分析");
        }
        ImportRequest importRequest = new ImportRequest(
                req.driver(), req.url(), req.username(), req.password(), req.types(), req.assetPrefix(), false);
        if ("SQL_FILE".equalsIgnoreCase(session.sourceType())) {
            return importService.doImportSql(session.artifact(), importRequest, adminRequestContext.getUserId());
        }
        return importService.doImport(importRequest, adminRequestContext.getUserId());
    }

    @GET
    @Path("/stream")
    @Blocking
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(summary = "获取导入进度 SSE 流")
    public Multi<ImportProgressEvent> stream() {
        Multi<ImportProgressEvent> events = importService.getEventStream();
        // 每 15 秒发送一个心跳包，防止网络连接超时断开
        Multi<ImportProgressEvent> ticks = Multi.createFrom().ticks().every(Duration.ofSeconds(15))
                .map(tick -> importService.getProgressHeartbeat());
        return Multi.createBy().merging().streams(events, ticks);
    }

    private void requireSuperAdmin() {
        if (!adminRequestContext.isSuperAdmin()) {
            throw new ForbiddenException("只有超级管理员可以执行导入操作");
        }
    }
}
