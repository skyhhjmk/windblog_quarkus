package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.CdnCacheDiagnosticsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

@Path("/api/admin/system/cdn-cache")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminCdnCache")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminCdnCacheController {

    @Inject
    CdnCacheDiagnosticsService cdnCacheDiagnosticsService;

    @GET
    @Path("/status")
    @Operation(summary = "获取CDN缓存健康检查与诊断报告", description = "探测核心端点的缓存标头并返回推荐配置")
    public Response getStatus(@QueryParam("baseUrl") String baseUrl) {
        var report = cdnCacheDiagnosticsService.runDiagnostics(baseUrl);
        return Response.ok(Map.of("success", true, "data", report)).build();
    }

    @GET
    @Path("/recommendations")
    @Operation(summary = "获取持久推荐的CDN缓存配置", description = "包含缓存键Cookie、Bypass规则等")
    public Response getRecommendations() {
        var recommendations = cdnCacheDiagnosticsService.getRecommendations();
        return Response.ok(Map.of("success", true, "data", recommendations)).build();
    }

    @POST
    @Path("/probe")
    @Operation(summary = "自定义探测单一路径的缓存标头", description = "测试指定URL或Header返回的Cache-Control")
    public Response probeCustom(ProbeRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "探测路径不能为空"))
                    .build();
        }
        var item = cdnCacheDiagnosticsService.probeCustom(
                request.baseUrl(), request.path(), request.headers());
        return Response.ok(Map.of("success", true, "data", item)).build();
    }

    public record ProbeRequest(String baseUrl, String path, Map<String, String> headers) {}
}
