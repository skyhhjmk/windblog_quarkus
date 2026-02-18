package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.AdminPostDetail;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.AdminPostItem;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.PageResult;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.PostCreateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.PostUpdateRequest;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.User;
import io.quarkus.panache.common.Page;
import jakarta.inject.Inject;
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
import java.util.Objects;

@Path("/api/admin/posts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminPost")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminPostApiController {

    @Inject
    com.biliwind.blog.service.ai.AiTaskProducer aiTaskProducer;

    @POST
    @Path("/{id}/ai-summary")
    @Transactional
    @Operation(summary = "触发 AI 总结")
    @APIResponse(responseCode = "200", description = "任务已提交")
    public Response triggerAiSummary(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        if (post.currentRevision == null) {
            throw badRequest("文章没有内容，无法生成总结");
        }

        aiTaskProducer.sendSummaryTask(new com.biliwind.blog.service.ai.AiSummaryTask(
                post.id,
                post.currentRevision.contentMarkdown,
                1 // Medium priority
        ));

        return Response.ok(Map.of("success", true, "message", "AI summary task submitted")).build();
    }

    @GET
    @Transactional
    @Operation(summary = "分页查询文章")
    @APIResponse(responseCode = "200", description = "成功", content = @Content(schema = @Schema(implementation = PageResult.class)))
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

        var query = Post.find(where + " order by updatedAt desc", parameters);
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
        post.password = request.password();
        post.seoTitle = request.seoTitle();
        post.seoKeywords = request.seoKeywords();
        post.seoDescription = request.seoDescription();
        post.renderType = resolveRenderType(request.renderType());
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

        boolean changed = false;

        if (request.slug() != null && !request.slug().isBlank()) {
            String nextSlug = request.slug().trim();
            if (!nextSlug.equals(post.slug) && Post.count("slug = ?1", nextSlug) > 0) {
                throw conflict("slug 已存在");
            }
            if (!nextSlug.equals(post.slug)) {
                changed = true;
            }
            post.slug = nextSlug;
        }

        if (request.summary() != null) {
            if (!Objects.equals(post.summary, request.summary())) {
                changed = true;
            }
            post.summary = request.summary();
        }
        if (request.aiSummary() != null) {
            if (!Objects.equals(post.aiSummary, request.aiSummary())) {
                changed = true;
            }
            post.aiSummary = request.aiSummary();
        }
        if (request.visibility() != null) {
            if (post.visibility != request.visibility()) {
                changed = true;
            }
            post.visibility = request.visibility();
        }
        if (request.password() != null) {
            if (!Objects.equals(post.password, request.password())) {
                changed = true;
            }
            post.password = request.password();
        }
        if (request.seoTitle() != null) {
            if (!Objects.equals(post.seoTitle, request.seoTitle())) {
                changed = true;
            }
            post.seoTitle = request.seoTitle();
        }
        if (request.seoKeywords() != null) {
            if (!Objects.equals(post.seoKeywords, request.seoKeywords())) {
                changed = true;
            }
            post.seoKeywords = request.seoKeywords();
        }
        if (request.seoDescription() != null) {
            if (!Objects.equals(post.seoDescription, request.seoDescription())) {
                changed = true;
            }
            post.seoDescription = request.seoDescription();
        }
        if (request.renderType() != null) {
            PostRenderType nextRenderType = requireRenderType(request.renderType());
            if (post.renderType != nextRenderType) {
                changed = true;
            }
            post.renderType = nextRenderType;
        }
        if (request.status() != null) {
            if (post.status != request.status()) {
                changed = true;
            }
            post.status = request.status();
            if (request.status() == 1 && post.publishedAt == null) {
                post.publishedAt = OffsetDateTime.now();
            }
        }

        if (request.title() != null || request.contentMarkdown() != null || request.editorType() != null) {
            Map<String, String> nextTitle = request.title() == null ? post.title : request.title();
            Map<String, String> currentContent = post.currentRevision == null ? Map.of()
                    : post.currentRevision.contentMarkdown;
            Map<String, String> nextContent = request.contentMarkdown() == null ? currentContent
                    : request.contentMarkdown();
            short currentEditorType = post.currentRevision == null ? 0 : post.currentRevision.editorType;
            short nextEditorType = request.editorType() == null ? currentEditorType : request.editorType();

            boolean revisionChanged = !Objects.equals(nextTitle, post.title)
                    || !Objects.equals(nextContent, currentContent)
                    || nextEditorType != currentEditorType;
            if (!revisionChanged) {
                throw conflict("内容相同");
            }

            User operator = mustFindOperator(requestContext);
            PostRevision nextRevision = new PostRevision();
            nextRevision.post = post;
            nextRevision.title = nextTitle;
            nextRevision.contentMarkdown = nextContent;
            nextRevision.editorType = nextEditorType;
            nextRevision.revisionNumber = nextRevisionNumber(post.id);
            nextRevision.createdBy = operator;
            nextRevision.createdAt = OffsetDateTime.now();
            nextRevision.persist();

            post.currentRevision = nextRevision;
            post.title = nextRevision.title;
            changed = true;
        }

        if (!changed) {
            throw conflict("内容相同");
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
    @APIResponse(responseCode = "409", description = "内容相同")
    public AdminPostDetail publish(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        if (post.status == 1) {
            throw conflict("内容相同");
        }
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

    private WebApplicationException badRequest(String message) {
        return new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("success", false, "message", message))
                .build());
    }

    private PostRenderType resolveRenderType(Short renderTypeCode) {
        if (renderTypeCode == null) {
            return PostRenderType.MARKDOWN;
        }
        return requireRenderType(renderTypeCode);
    }

    private PostRenderType requireRenderType(Short renderTypeCode) {
        if (!PostRenderType.isSupportedCode(renderTypeCode)) {
            throw badRequest(
                    "renderType 不合法，可选值：0(markdown),1(html),2(vditor),3(v_builder),4(gutenberg),5(flutter_quill),6(flutter_markdown_plus)");
        }
        return PostRenderType.fromCode(renderTypeCode);
    }

    private AdminPostItem toItem(Post post) {
        return new AdminPostItem(
                post.id,
                post.slug,
                post.title,
                post.status,
                post.visibility,
                post.renderType == null ? PostRenderType.MARKDOWN.code() : post.renderType.code(),
                post.version,
                post.user == null ? null : post.user.id,
                post.publishedAt,
                post.createdAt,
                post.updatedAt);
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
                post.password,
                post.seoTitle,
                post.seoKeywords,
                post.seoDescription,
                post.renderType == null ? PostRenderType.MARKDOWN.code() : post.renderType.code(),
                post.currentRevision == null ? 0 : post.currentRevision.editorType,
                post.currentRevision == null ? 0 : post.currentRevision.revisionNumber,
                post.version,
                post.user == null ? null : post.user.id,
                post.publishedAt,
                post.createdAt,
                post.updatedAt);
    }
}
