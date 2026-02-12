package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.AdminPostDetail;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.AdminPostItem;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.PageResult;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.PostCreateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.PostUpdateRequest;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.User;
import io.quarkus.panache.common.Page;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/posts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminPost")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminPostApiController {

    @GET
    @Transactional
    @Operation(summary = "分页查询文章")
    @APIResponse(responseCode = "200", description = "成功",
            content = @Content(schema = @Schema(implementation = PageResult.class)))
    public PageResult<AdminPostItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("10") int pageSize,
            @QueryParam("status") Short status,
            @QueryParam("keyword") String keyword) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        StringBuilder where = new StringBuilder("deletedAt is null");
        Map<String, Object> parameters = new HashMap<>();

        if (status != null) {
            where.append(" and status = :status");
            parameters.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and lower(slug) like :keyword");
            parameters.put("keyword", "%" + keyword.trim().toLowerCase() + "%");
        }

        var query = Post.find(where.toString() + " order by updatedAt desc", parameters);
        long total = query.count();
        List<Post> entities = query.page(Page.of(safePage - 1, safePageSize)).list();

        List<AdminPostItem> items = entities.stream().map(this::toItem).toList();
        return new PageResult<>(items, total, safePage, safePageSize);
    }

    @GET
    @Path("/{id}")
    @Transactional
    @Operation(summary = "查询文章详情")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    public AdminPostDetail detail(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        return toDetail(post);
    }

    @POST
    @Transactional
    @Operation(summary = "创建文章草稿")
    @APIResponse(responseCode = "200", description = "创建成功")
    @APIResponse(responseCode = "409", description = "slug 冲突")
    public AdminPostDetail create(@Valid PostCreateRequest request,
                                  @Context ContainerRequestContext requestContext) {
        if (Post.count("slug = ?1", request.slug().trim()) > 0) {
            throw conflict("slug 已存在");
        }

        User operator = mustFindOperator(requestContext);
        OffsetDateTime now = OffsetDateTime.now();

        Post post = new Post();
        post.slug = request.slug().trim();
        post.title = request.title();
        post.summary = request.summary();
        post.aiSummary = request.aiSummary();
        post.status = request.status() == null ? 0 : request.status();
        post.visibility = request.visibility() == null ? 0 : request.visibility();
        post.user = operator;
        post.createdAt = now;
        post.updatedAt = now;
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = request.title();
        revision.contentMarkdown = request.contentMarkdown();
        revision.editorType = request.editorType() == null ? 0 : request.editorType();
        revision.revisionNumber = 1;
        revision.createdBy = operator;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        if (post.status == 1) {
            post.publishedAt = now;
        }

        return toDetail(post);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "编辑文章")
    @APIResponse(responseCode = "200", description = "更新成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    @APIResponse(responseCode = "409", description = "版本冲突或 slug 冲突")
    public AdminPostDetail update(@PathParam("id") Long id,
                                  @Valid PostUpdateRequest request,
                                  @Context ContainerRequestContext requestContext) {
        Post post = mustFindPost(id);
        if (!post.version.equals(request.version())) {
            throw conflict("版本冲突，请刷新后重试");
        }

        if (request.slug() != null && !request.slug().isBlank()) {
            String nextSlug = request.slug().trim();
            if (!nextSlug.equals(post.slug) && Post.count("slug = ?1", nextSlug) > 0) {
                throw conflict("slug 已存在");
            }
            post.slug = nextSlug;
        }

        if (request.summary() != null) {
            post.summary = request.summary();
        }
        if (request.aiSummary() != null) {
            post.aiSummary = request.aiSummary();
        }
        if (request.visibility() != null) {
            post.visibility = request.visibility();
        }
        if (request.status() != null) {
            post.status = request.status();
            if (request.status() == 1 && post.publishedAt == null) {
                post.publishedAt = OffsetDateTime.now();
            }
        }

        if (request.title() != null || request.contentMarkdown() != null || request.editorType() != null) {
            User operator = mustFindOperator(requestContext);
            PostRevision nextRevision = new PostRevision();
            nextRevision.post = post;
            nextRevision.title = request.title() == null ? post.title : request.title();
            nextRevision.contentMarkdown = request.contentMarkdown() == null
                    ? (post.currentRevision == null ? Map.of() : post.currentRevision.contentMarkdown)
                    : request.contentMarkdown();
            nextRevision.editorType = request.editorType() == null
                    ? (post.currentRevision == null ? 0 : post.currentRevision.editorType)
                    : request.editorType();
            nextRevision.revisionNumber = nextRevisionNumber(post.id);
            nextRevision.createdBy = operator;
            nextRevision.createdAt = OffsetDateTime.now();
            nextRevision.persist();

            post.currentRevision = nextRevision;
            post.title = nextRevision.title;
        }

        post.updatedAt = OffsetDateTime.now();
        return toDetail(post);
    }

    @POST
    @Path("/{id}/publish")
    @Transactional
    @Operation(summary = "发布文章")
    @APIResponse(responseCode = "200", description = "发布成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    public AdminPostDetail publish(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        post.status = 1;
        if (post.publishedAt == null) {
            post.publishedAt = OffsetDateTime.now();
        }
        post.updatedAt = OffsetDateTime.now();
        return toDetail(post);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "软删除文章")
    @APIResponse(responseCode = "200", description = "删除成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    public Response delete(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        OffsetDateTime now = OffsetDateTime.now();
        post.deletedAt = now;
        post.updatedAt = now;
        return Response.ok(Map.of("success", true, "id", id)).build();
    }

    private Post mustFindPost(Long id) {
        Post post = Post.find("id = ?1 and deletedAt is null", id).firstResult();
        if (post == null) {
            throw new NotFoundException("文章不存在");
        }
        return post;
    }

    private User mustFindOperator(ContainerRequestContext requestContext) {
        Object value = requestContext.getProperty(AdminJwtAuthFilter.REQUEST_USER_ID_KEY);
        if (!(value instanceof Number number)) {
            throw new WebApplicationException("未登录", Response.Status.UNAUTHORIZED);
        }
        User user = User.find("id = ?1 and status = 1 and deletedAt is null", number.longValue()).firstResult();
        if (user == null) {
            throw new WebApplicationException("用户不存在或已禁用", Response.Status.UNAUTHORIZED);
        }
        return user;
    }

    private int nextRevisionNumber(Long postId) {
        PostRevision latest = PostRevision.find("post.id = ?1 order by revisionNumber desc", postId).firstResult();
        if (latest == null) {
            return 1;
        }
        return latest.revisionNumber + 1;
    }

    private WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(Response.Status.CONFLICT)
                .entity(Map.of("success", false, "message", message))
                .build());
    }

    private AdminPostItem toItem(Post post) {
        return new AdminPostItem(
                post.id,
                post.slug,
                post.title,
                post.status,
                post.visibility,
                post.version,
                post.user == null ? null : post.user.id,
                post.publishedAt,
                post.createdAt,
                post.updatedAt
        );
    }

    private AdminPostDetail toDetail(Post post) {
        return new AdminPostDetail(
                post.id,
                post.slug,
                post.title,
                post.summary,
                post.aiSummary,
                post.currentRevision == null ? Map.of() : post.currentRevision.contentMarkdown,
                post.status,
                post.visibility,
                post.currentRevision == null ? 0 : post.currentRevision.editorType,
                post.currentRevision == null ? 0 : post.currentRevision.revisionNumber,
                post.version,
                post.user == null ? null : post.user.id,
                post.publishedAt,
                post.createdAt,
                post.updatedAt
        );
    }
}
