package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.service.elasticsearch.ElasticsearchConnectionManager;
import com.biliwind.blog.service.elasticsearch.ElasticsearchIndexService;
import com.biliwind.blog.service.elasticsearch.ElasticsearchLogBufferService;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostSearchService;
import com.biliwind.blog.service.elasticsearch.ElasticsearchSettingsService;
import com.biliwind.blog.service.edge.NodeRoleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/elasticsearch")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "AdminElasticsearch")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminElasticsearchController {

    @Inject
    ElasticsearchPostSearchService postSearchService;

    @Inject
    ElasticsearchIndexService indexService;

    @Inject
    ElasticsearchConnectionManager connectionManager;

    @Inject
    ElasticsearchLogBufferService logBufferService;
    @Inject
    NodeRoleService nodeRoleService;

    @GET
    @Path("/health")
    @Operation(summary = "Elasticsearch 健康检查", description = "检查 Elasticsearch 连接状态、ILM 策略和索引模板是否存在")
    @APIResponse(responseCode = "200", description = "健康检查结果")
    public Response health() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.ok(edgeDisabledResponse()).build();
        }

        var connectionStatus = connectionManager.getStatus();
        var postSearchStatus = postSearchService.getServiceStatus();
        var indexServiceStatus = indexService.getServiceStatus();

        Map<String, Object> result = new HashMap<>();
        result.put("connection", Map.of(
                "initialized", connectionStatus.initialized(),
                "available", connectionStatus.available(),
                "enabled", connectionStatus.enabled(),
                "hosts", connectionStatus.hosts(),
                "status", connectionStatus.status(),
                "lastError", connectionStatus.lastError() != null
                        ? SensitiveMessageSanitizer.sanitize(connectionStatus.lastError()) : ""
        ));
        result.put("postSearch", Map.of(
                "connectionAvailable", postSearchStatus.connectionAvailable(),
                "indexInitialized", postSearchStatus.indexInitialized(),
                "healthStatus", postSearchStatus.healthStatus()
        ));
        result.put("logIndex", Map.of(
                "connectionAvailable", indexServiceStatus.connectionAvailable(),
                "indexInitialized", indexServiceStatus.indexInitialized(),
                "healthStatus", indexServiceStatus.healthStatus()
        ));
        result.put("logBuffer", Map.of(
                "bufferedCount", logBufferService.getBufferedCount(),
                "droppedCount", logBufferService.getDroppedCount(),
                "sentCount", logBufferService.getSentCount(),
                "queueSize", logBufferService.getQueueSize(),
                "isReady", logBufferService.isReady()
        ));

        boolean allHealthy = connectionStatus.available() &&
                postSearchStatus.indexInitialized() &&
                indexServiceStatus.indexInitialized();

        if (allHealthy) {
            return Response.ok(result).build();
        } else {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(result)
                    .build();
        }
    }

    @GET
    @Path("/status")
    @Operation(summary = "获取详细状态", description = "获取 Elasticsearch 各组件的详细状态信息")
    @APIResponse(responseCode = "200", description = "详细状态信息")
    public Response status() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.ok(edgeDisabledResponse()).build();
        }

        Map<String, Object> result = new HashMap<>();

        var connectionStatus = connectionManager.getStatus();
        result.put("elasticsearch", Map.of(
                "initialized", connectionStatus.initialized(),
                "available", connectionStatus.available(),
                "enabled", connectionStatus.enabled(),
                "hosts", connectionStatus.hosts(),
                "status", connectionStatus.status(),
                "lastError", connectionStatus.lastError() != null
                        ? SensitiveMessageSanitizer.sanitize(connectionStatus.lastError()) : ""
        ));

        try {
            var healthResult = postSearchService.checkHealth();
            result.put("postSearchHealth", Map.of(
                    "status", healthResult.status(),
                    "ilmPolicyExists", healthResult.ilmPolicyExists(),
                    "indexTemplateExists", healthResult.indexTemplateExists()
            ));
        } catch (Exception e) {
            result.put("postSearchHealth", Map.of(
                    "status", "error",
                    "message", SensitiveMessageSanitizer.sanitize(e.getMessage())
            ));
        }

        var logIndexStatus = indexService.getServiceStatus();
        result.put("logIndex", Map.of(
                "connectionAvailable", logIndexStatus.connectionAvailable(),
                "indexInitialized", logIndexStatus.indexInitialized(),
                "healthStatus", logIndexStatus.healthStatus()
        ));

        result.put("logBuffer", Map.of(
                "bufferedCount", logBufferService.getBufferedCount(),
                "droppedCount", logBufferService.getDroppedCount(),
                "sentCount", logBufferService.getSentCount(),
                "queueSize", logBufferService.getQueueSize(),
                "isReady", logBufferService.isReady()
        ));

        return Response.ok(result).build();
    }

    @POST
    @Path("/repair-log-alias")
    @Operation(summary = "修复日志写入别名", description = "修复日志 rollover 别名的多个写入索引状态，不删除历史索引")
    @APIResponse(responseCode = "200", description = "修复完成")
    @APIResponse(responseCode = "503", description = "Elasticsearch 服务不可用")
    public Response repairLogAlias() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }
        if (!connectionManager.isAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "success", false,
                            "message", "Elasticsearch 当前不可用"
                    ))
                    .build();
        }

        try {
            indexService.repairWriteAlias();
            return Response.ok(Map.of(
                    "success", true,
                    "message", "日志写入别名已修复，历史索引未删除"
            )).build();
        } catch (Exception exception) {
            return Response.serverError().entity(Map.of(
                    "success", false,
                    "message", SensitiveMessageSanitizer.sanitize(exception.getMessage())
            )).build();
        }
    }

    @GET
    @Path("/ilm-policy")
    @Operation(summary = "获取 ILM 策略", description = "获取文章索引的 ILM（索引生命周期管理）策略配置")
    @APIResponse(responseCode = "200", description = "ILM 策略详情")
    @APIResponse(responseCode = "503", description = "Elasticsearch 服务不可用")
    public Response getIlmPolicy() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }

        try {
            if (!connectionManager.isAvailable()) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of(
                                "error", "Elasticsearch 服务当前不可用",
                                "status", "unavailable",
                                "message", "请稍后重试或检查 Elasticsearch 服务状态"
                        ))
                        .build();
            }

            String policyJson = postSearchService.getIlmPolicy();
            return Response.ok(policyJson).type(MediaType.APPLICATION_JSON).build();
        } catch (ElasticsearchPostSearchService.ElasticsearchUnavailableException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "error", SensitiveMessageSanitizer.sanitize(e.getMessage()),
                            "status", "unavailable"
                    ))
                    .build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", SensitiveMessageSanitizer.sanitize(e.getMessage()))).build();
        }
    }

    @GET
    @Path("/index-template")
    @Operation(summary = "获取索引模板", description = "获取文章索引的模板配置，包含 IK 分词器设置和字段映射")
    @APIResponse(responseCode = "200", description = "索引模板详情")
    @APIResponse(responseCode = "503", description = "Elasticsearch 服务不可用")
    public Response getIndexTemplate() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }

        try {
            if (!connectionManager.isAvailable()) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of(
                                "error", "Elasticsearch 服务当前不可用",
                                "status", "unavailable",
                                "message", "请稍后重试或检查 Elasticsearch 服务状态"
                        ))
                        .build();
            }

            String templateJson = postSearchService.getIndexTemplate();
            return Response.ok(templateJson).type(MediaType.APPLICATION_JSON).build();
        } catch (ElasticsearchPostSearchService.ElasticsearchUnavailableException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "error", SensitiveMessageSanitizer.sanitize(e.getMessage()),
                            "status", "unavailable"
                    ))
                    .build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", SensitiveMessageSanitizer.sanitize(e.getMessage()))).build();
        }
    }

    @GET
    @Path("/posts/search")
    @Operation(summary = "搜索文章", description = "使用 Elasticsearch 搜索已发布的文章，支持分页和关键词搜索")
    @APIResponse(responseCode = "200", description = "搜索结果列表")
    @APIResponse(responseCode = "503", description = "Elasticsearch 服务不可用")
    public Response searchPosts(
            @QueryParam("q") @DefaultValue("") String query,
            @QueryParam("region") @DefaultValue("global") String region,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size) {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }

        try {
            if (!postSearchService.isAvailable()) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of(
                                "error", "Elasticsearch 搜索服务当前不可用",
                                "status", "unavailable",
                                "message", "搜索功能已回退到数据库搜索，部分高级搜索功能可能受限",
                                "fallback", true
                        ))
                        .build();
            }

            if (page < 1) page = 1;
            if (size < 1 || size > 100) size = 10;

            var searchResult = query == null || query.trim().isEmpty()
                    ? postSearchService.searchPosts("", page, size, null, null, null, region)
                    : postSearchService.searchPosts(query, page, size, null, null, null, region);

            Map<String, Object> result = new HashMap<>();
            result.put("total", searchResult.total());
            result.put("page", page);
            result.put("size", size);
            result.put("items", searchResult.posts());
            result.put("source", "elasticsearch");
            return Response.ok(result).build();
        } catch (ElasticsearchPostSearchService.ElasticsearchUnavailableException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "error", SensitiveMessageSanitizer.sanitize(e.getMessage()),
                            "status", "unavailable",
                            "message", "搜索功能已回退到数据库搜索",
                            "fallback", true
                    ))
                    .build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", SensitiveMessageSanitizer.sanitize(e.getMessage()))).build();
        }
    }

    @POST
    @Path("/posts/{id}/index")
    @Operation(summary = "手动索引文章", description = "手动将指定文章重新索引到 Elasticsearch，包括关联的 tags 数据")
    @APIResponse(responseCode = "200", description = "索引成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    @APIResponse(responseCode = "503", description = "Elasticsearch 服务不可用")
    @APIResponse(responseCode = "500", description = "索引失败")
    public Response indexPost(@PathParam("id") Long id) {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }

        try {
            if (!connectionManager.isAvailable()) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of(
                                "success", false,
                                "error", "Elasticsearch 服务当前不可用",
                                "status", "unavailable",
                                "message", "请稍后重试或检查 Elasticsearch 服务状态"
                        ))
                        .build();
            }

            Post post = Post.findById(id);
            if (post == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "文章不存在: " + id))
                        .build();
            }

            List<String> tags = postSearchService.getPostTags(id);
            postSearchService.indexPost(post, tags);

            return Response.ok(Map.of(
                    "success", true,
                    "message", "文章索引成功",
                    "postId", id,
                    "tagsCount", tags.size()
            )).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of(
                    "success", false,
                    "error", SensitiveMessageSanitizer.sanitize(e.getMessage())
            )).build();
        }
    }

    @DELETE
    @Path("/posts/{id}/index")
    @Operation(summary = "删除文章索引", description = "从 Elasticsearch 中删除指定文章的索引数据")
    @APIResponse(responseCode = "200", description = "删除成功")
    @APIResponse(responseCode = "503", description = "Elasticsearch 服务不可用")
    @APIResponse(responseCode = "500", description = "删除失败")
    public Response deletePostIndex(@PathParam("id") Long id) {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }

        try {
            if (!connectionManager.isAvailable()) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of(
                                "success", false,
                                "error", "Elasticsearch 服务当前不可用",
                                "status", "unavailable",
                                "message", "请稍后重试或检查 Elasticsearch 服务状态"
                        ))
                        .build();
            }

            postSearchService.deletePostIndex(id);
            return Response.ok(Map.of(
                    "success", true,
                    "message", "文章索引已删除",
                    "postId", id
            )).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of(
                    "success", false,
                    "error", SensitiveMessageSanitizer.sanitize(e.getMessage())
            )).build();
        }
    }

    @POST
    @Path("/posts/reindex-all")
    @Operation(summary = "全量索引文章", description = "将所有已发布的文章重新索引到 Elasticsearch，用于更新索引结构或修复数据")
    @APIResponse(responseCode = "200", description = "索引任务启动成功")
    public Response reindexAll() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }

        try {
            if (!connectionManager.isAvailable()) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of("success", false, "message", "Elasticsearch 当前不可用"))
                        .build();
            }
            List<Post> posts = Post.list(
                    "status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null",
                    com.biliwind.blog.model.PostStatus.PUBLISHED);
            int count = 0;
            for (Post post : posts) {
                List<String> tags = postSearchService.getPostTags(post.id);
                postSearchService.indexPost(post, tags);
                count++;
            }
            return Response.ok(Map.of(
                    "success", true,
                    "message", "全量索引完成",
                    "totalCount", count
            )).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", SensitiveMessageSanitizer.sanitize(e.getMessage()))).build();
        }
    }

    @POST
    @Path("/test-connection")
    @Operation(summary = "测试 Elasticsearch 连接")
    public Response testConnection() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }

        boolean connected = connectionManager.testConnection();
        ElasticsearchSettingsService.ElasticsearchSettings settings = connectionManager.getCurrentSettings();
        Response.Status responseStatus = Response.Status.SERVICE_UNAVAILABLE;
        String message = "连接失败，请检查地址、凭据和服务状态";
        if (connected) {
            responseStatus = Response.Status.OK;
            message = "连接成功";
        }
        return Response.status(responseStatus)
                .entity(Map.of(
                        "success", connected,
                        "enabled", settings.enabled(),
                        "hosts", settings.hosts(),
                        "message", message
                ))
                .build();
    }

    @POST
    @Path("/apply-index-configuration")
    @Operation(summary = "应用分词器和同义词配置")
    public Response applyIndexConfiguration() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }
        if (!connectionManager.isAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("success", false, "message", "Elasticsearch 当前不可用"))
                    .build();
        }
        try {
            postSearchService.applyIndexConfiguration();
            return Response.ok(Map.of(
                    "success", true,
                    "message", "索引模板已更新；已有索引需要重建后才能使用新的分词配置"
            )).build();
        } catch (Exception exception) {
            return Response.serverError().entity(Map.of(
                    "success", false,
                    "message", SensitiveMessageSanitizer.sanitize(exception.getMessage())
            )).build();
        }
    }

    @POST
    @Path("/rebuild")
    @Operation(summary = "重建文章索引")
    public Response rebuild() {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }
        if (!connectionManager.isAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("success", false, "message", "Elasticsearch 当前不可用"))
                    .build();
        }
        try {
            postSearchService.rebuildPostIndex();
            return reindexAll();
        } catch (Exception exception) {
            return Response.serverError().entity(Map.of(
                    "success", false,
                    "message", SensitiveMessageSanitizer.sanitize(exception.getMessage())
            )).build();
        }
    }

    @POST
    @Path("/analyze")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "预览分词结果")
    public Response analyze(Map<String, Object> body) {
        if (nodeRoleService.isEdgeNode()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(edgeDisabledResponse())
                    .build();
        }
        Object textValue = body.get("text");
        String text = "";
        if (textValue != null) {
            text = textValue.toString().trim();
        }
        if (text.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "预览文本不能为空"))
                    .build();
        }
        try {
            List<String> tokens = postSearchService.analyzeText(text);
            return Response.ok(Map.of(
                    "success", true,
                    "tokens", tokens
            )).build();
        } catch (Exception exception) {
            return Response.serverError().entity(Map.of(
                    "success", false,
                    "message", SensitiveMessageSanitizer.sanitize(exception.getMessage())
            )).build();
        }
    }

    private Map<String, Object> edgeDisabledResponse() {
        Map<String, Object> result = new HashMap<>();
        result.put("disabled", true);
        result.put("status", "disabled");
        result.put("message", "当前节点是边缘节点，不运行 Elasticsearch。普通搜索使用本地数据库，深度搜索通过 gRPC 回源主节点。");
        return result;
    }
}
