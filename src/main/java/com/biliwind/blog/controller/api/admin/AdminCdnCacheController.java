package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.CdnCacheDiagnosticsService;
import com.biliwind.blog.service.StaticHtmlCacheService;
import com.biliwind.blog.service.StaticHtmlWarmupService;
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

    @Inject
    StaticHtmlCacheService staticHtmlCacheService;

    @Inject
    StaticHtmlWarmupService staticHtmlWarmupService;

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

    @GET
    @Path("/static/status")
    @Operation(summary = "获取全站静态HTML文件缓存状态", description = "返回缓存开关、目录、文件数、磁盘占用、命中次数与条目列表")
    public Response getStaticCacheStatus() {
        var status = staticHtmlCacheService.getStatusSnapshot();
        return Response.ok(Map.of("success", true, "data", status)).build();
    }

    @POST
    @Path("/static/toggle")
    @Operation(summary = "开关全站静态HTML文件缓存", description = "动态启用或暂停全站静态HTML缓存")
    public Response toggleStaticCache(ToggleStaticCacheRequest request) {
        if (request == null || request.enabled() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "enabled 参数不能为空"))
                    .build();
        }
        staticHtmlCacheService.setEnabled(request.enabled());
        return Response.ok(Map.of(
                "success", true,
                "data", staticHtmlCacheService.getStatusSnapshot()
        )).build();
    }

    @POST
    @Path("/static/purge")
    @Operation(summary = "手动清理指定静态HTML缓存", description = "按缓存键 key 或路径前缀 path 清理静态 HTML 文件")
    public Response purgeStaticCache(PurgeStaticCacheRequest request) {
        if (request == null || ((request.key() == null || request.key().isBlank())
                && (request.path() == null || request.path().isBlank()))) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "请提供 key 或 path"))
                    .build();
        }
        int purgedCount = 0;
        if (request.key() != null && !request.key().isBlank()) {
            if (staticHtmlCacheService.invalidateByKey(request.key().trim())) {
                purgedCount = 1;
            }
        } else {
            purgedCount = staticHtmlCacheService.invalidateByPathPrefix(request.path().trim());
        }
        return Response.ok(Map.of(
                "success", true,
                "purgedCount", purgedCount,
                "data", staticHtmlCacheService.getStatusSnapshot()
        )).build();
    }

    @POST
    @Path("/static/purge-all")
    @Operation(summary = "一键全清全站静态HTML缓存", description = "删除磁盘上全部已生成的静态 HTML 文件与索引")
    public Response purgeAllStaticCache() {
        int purgedCount = staticHtmlCacheService.invalidateAll();
        return Response.ok(Map.of(
                "success", true,
                "purgedCount", purgedCount,
                "data", staticHtmlCacheService.getStatusSnapshot()
        )).build();
    }

    @POST
    @Path("/static/warmup")
    @Operation(summary = "手动触发全站静态缓存预生成", description = "异步遍历全站公开页面与 PJAX 片段并预生成静态 HTML 文件")
    public Response startStaticCacheWarmup(WarmupRequest request) {
        boolean includePjax = request == null || request.includePjax() == null || request.includePjax();
        try {
            var status = staticHtmlWarmupService.startWarmup(includePjax);
            return Response.ok(Map.of("success", true, "data", status)).build();
        } catch (IllegalStateException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", ex.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/static/warmup/status")
    @Operation(summary = "获取全站静态缓存预生成进度", description = "返回当前异步预生成任务的执行状态与计数")
    public Response getStaticCacheWarmupStatus() {
        return Response.ok(Map.of("success", true, "data", staticHtmlWarmupService.getStatus())).build();
    }

    @POST
    @Path("/static/warmup/cancel")
    @Operation(summary = "取消全站静态缓存预生成", description = "请求中止正在运行的异步预生成任务")
    public Response cancelStaticCacheWarmup() {
        return Response.ok(Map.of("success", true, "data", staticHtmlWarmupService.cancelWarmup())).build();
    }

    public record ProbeRequest(String baseUrl, String path, Map<String, String> headers) {}

    public record ToggleStaticCacheRequest(Boolean enabled) {}

    public record PurgeStaticCacheRequest(String key, String path) {}

    public record WarmupRequest(Boolean includePjax) {}
}
