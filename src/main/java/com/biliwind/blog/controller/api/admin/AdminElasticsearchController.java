package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostSearchService;
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

    @GET
    @Path("/health")
    @Operation(summary = "Elasticsearch 健康检查", description = "检查 Elasticsearch 连接状态、ILM 策略和索引模板是否存在")
    @APIResponse(responseCode = "200", description = "健康检查结果")
    public Response health() {
        try {
            var healthResult = postSearchService.checkHealth();
            Map<String, Object> result = new HashMap<>();
            result.put("status", healthResult.status());
            result.put("ilmPolicyExists", healthResult.ilmPolicyExists());
            result.put("indexTemplateExists", healthResult.indexTemplateExists());
            return Response.ok(result).build();
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return Response.serverError().entity(error).build();
        }
    }

    @GET
    @Path("/ilm-policy")
    @Operation(summary = "获取 ILM 策略", description = "获取文章索引的 ILM（索引生命周期管理）策略配置")
    @APIResponse(responseCode = "200", description = "ILM 策略详情")
    public Response getIlmPolicy() {
        try {
            String policyJson = postSearchService.getIlmPolicy();
            return Response.ok(policyJson).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/index-template")
    @Operation(summary = "获取索引模板", description = "获取文章索引的模板配置，包含 IK 分词器设置和字段映射")
    @APIResponse(responseCode = "200", description = "索引模板详情")
    public Response getIndexTemplate() {
        try {
            String templateJson = postSearchService.getIndexTemplate();
            return Response.ok(templateJson).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/posts/search")
    @Operation(summary = "搜索文章", description = "使用 Elasticsearch 搜索已发布的文章，支持分页和关键词搜索")
    @APIResponse(responseCode = "200", description = "搜索结果列表")
    public Response searchPosts(
            @QueryParam("q") @DefaultValue("") String query,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size) {
        try {
            if (page < 1) page = 1;
            if (size < 1 || size > 100) size = 10;

            var searchResult = query == null || query.trim().isEmpty()
                    ? postSearchService.searchPosts("", page, size, null, null, null)
                    : postSearchService.searchPosts(query, page, size, null, null, null);

            Map<String, Object> result = new HashMap<>();
            result.put("total", searchResult.total());
            result.put("page", page);
            result.put("size", size);
            result.put("items", searchResult.posts());
            return Response.ok(result).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/posts/{id}/index")
    @Operation(summary = "手动索引文章", description = "手动将指定文章重新索引到 Elasticsearch，包括关联的 tags 数据")
    @APIResponse(responseCode = "200", description = "索引成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    @APIResponse(responseCode = "500", description = "索引失败")
    public Response indexPost(@PathParam("id") Long id) {
        try {
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
                    "error", e.getMessage()
            )).build();
        }
    }

    @DELETE
    @Path("/posts/{id}/index")
    @Operation(summary = "删除文章索引", description = "从 Elasticsearch 中删除指定文章的索引数据")
    @APIResponse(responseCode = "200", description = "删除成功")
    @APIResponse(responseCode = "500", description = "删除失败")
    public Response deletePostIndex(@PathParam("id") Long id) {
        try {
            postSearchService.deletePostIndex(id);
            return Response.ok(Map.of(
                    "success", true,
                    "message", "文章索引已删除",
                    "postId", id
            )).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of(
                    "success", false,
                    "error", e.getMessage()
            )).build();
        }
    }
}
