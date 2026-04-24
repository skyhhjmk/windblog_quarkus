package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.*;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.MediaManagementService;
import com.biliwind.blog.service.elasticsearch.PostSyncedEvent;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;

@Path("/api/admin/posts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@org.eclipse.microprofile.openapi.annotations.tags.Tag(name = "AdminPost")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminPostApiController {

    @Inject
    com.biliwind.blog.service.ai.AiTaskProducer aiTaskProducer;

    @Inject
    MediaManagementService mediaService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    Event<Post> postEvent;

    @Inject
    jakarta.enterprise.event.Event<PostSyncedEvent> esSyncEvent;

    @POST
    @Path("/{id}/ai-summary/trigger")
    @Transactional
    @Operation(summary = "手动触发 AI 摘要生成")
    @APIResponse(responseCode = "200", description = "触发成功")
    @APIResponse(responseCode = "400", description = "当前状态不允许或无内容")
    public Response triggerAiSummary(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        if (post.aiSummaryStatus != null && post.aiSummaryStatus > 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(java.util.Map.of("success", false, "message", "当前文章 AI 摘要已被锁定或禁用，无法手动触发"))
                    .build();
        }

        PostRevision rev = post.currentRevision;
        if (rev == null || rev.contentMarkdown == null || rev.contentMarkdown.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(java.util.Map.of("success", false, "message", "文章暂无内容"))
                    .build();
        }

        Long userId = adminRequestContext.getUserId();
        aiTaskProducer.sendSummaryTask(new com.biliwind.blog.service.ai.AiSummaryTask(
                post.id,
                rev.contentMarkdown,
                1, // Medium priority
                userId
        ));

        return Response.ok(java.util.Map.of("success", true, "message", "摘要任务已触发")).build();
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
    public AdminPostDetail create(@Valid PostCreateRequest request) {
        if (Post.count("slug = ?1", request.slug().trim()) > 0) {
            throw conflict("slug 已存在");
        }

        User operator = mustFindOperator();
        OffsetDateTime now = OffsetDateTime.now();

        Post post = new Post();
        post.slug = request.slug().trim();
        post.title = request.title();
        post.summary = request.summary();
        post.aiSummary = request.aiSummary();
        post.status = resolveStatus(request.status(), PostStatus.DRAFT);
        post.visibility = request.visibility() == null ? 0 : request.visibility();
        post.password = request.password();
        post.seoTitle = request.seoTitle();
        post.seoKeywords = request.seoKeywords();
        post.seoDescription = request.seoDescription();
        post.renderType = resolveRenderType(request.renderType());
        post.aiSummaryStatus = request.aiSummaryStatus() == null ? 0 : request.aiSummaryStatus();
        post.user = operator;
        post.tutorialLevelDefs = request.tutorialLevelDefs();
        post.createdAt = now;
        post.updatedAt = now;

        // 设置分类
        if (request.categoryId() != null) {
            post.category = Category.findById(request.categoryId());
        }

        post.persist();

        // 如果状态为自动(0)，则触发 AI 摘要任务
        if (post.aiSummaryStatus == 0 && request.contentMarkdown() != null && !request.contentMarkdown().isEmpty()) {
            aiTaskProducer.sendSummaryTask(new com.biliwind.blog.service.ai.AiSummaryTask(
                    post.id,
                    request.contentMarkdown(),
                    1,
                    operator.id
            ));
        }

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = request.title();
        revision.contentMarkdown = request.contentMarkdown();
        revision.contentBlocks = request.contentBlocks();
        revision.tutorialLevelDefs = request.tutorialLevelDefs();
        revision.editorType = request.editorType() == null ? 0 : request.editorType();
        revision.revisionNumber = 1;
        revision.createdBy = operator;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        mediaService.syncPostReferences(post, revision.contentMarkdown, revision.contentBlocks);

        // 设置标签关联
        if (request.tagIds() != null && !request.tagIds().isEmpty()) {
            for (Long tagId : request.tagIds()) {
                com.biliwind.blog.model.Tag tag = com.biliwind.blog.model.Tag.findById(tagId);
                if (tag != null) {
                    PostTag postTag = new PostTag();
                    postTag.id = new PostTagId(post.id, tag.id);
                    postTag.post = post;
                    postTag.tag = tag;
                    postTag.persist();
                }
            }
        }

        if (post.status == PostStatus.PUBLISHED) {
            post.publishedAt = now;
        }

        esSyncEvent.fire(new PostSyncedEvent(post.id));

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
                                  @Valid PostUpdateRequest request) {
        Post post = mustFindPost(id);
        if (!post.version.equals(request.version())) {
            throw conflict("版本冲突，请刷新后重试");
        }

        boolean changed = false;

        changed |= updateBasicFields(post, request);
        changed |= updateCategory(post, request);

        if (request.tagIds() != null) {
            changed |= updatePostTags(post, request.tagIds());
        }

        changed |= updateContent(post, request);

        if (!changed) {
            throw conflict("内容相同");
        }

        post.updatedAt = OffsetDateTime.now();

        // 如果状态为自动(0)且内容已更变，则触发 AI 摘要任务
        if (post.aiSummaryStatus == 0) {
            PostRevision rev = post.currentRevision;
            if (rev != null && rev.contentMarkdown != null && !rev.contentMarkdown.isEmpty()) {
                Long userId = adminRequestContext.getUserId();
                aiTaskProducer.sendSummaryTask(new com.biliwind.blog.service.ai.AiSummaryTask(
                        post.id,
                        rev.contentMarkdown,
                        1,
                        userId
                ));
            }
        }

        esSyncEvent.fire(new PostSyncedEvent(post.id));
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
        if (post.status == PostStatus.PUBLISHED) {
            throw conflict("内容相同");
        }
        post.status = PostStatus.PUBLISHED;
        if (post.publishedAt == null) {
            post.publishedAt = OffsetDateTime.now();
        }
        post.updatedAt = OffsetDateTime.now();
        esSyncEvent.fire(new PostSyncedEvent(post.id));
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
        esSyncEvent.fire(new PostSyncedEvent(post.id));
        return Response.ok(Map.of("success", true, "id", id)).build();
    }

    private Post mustFindPost(Long id) {
        Post post = Post.find("id = ?1 and deletedAt is null", id).firstResult();
        if (post == null) {
            throw new NotFoundException("文章不存在");
        }
        return post;
    }

    private User mustFindOperator() {
        Long userId = adminRequestContext.getUserId();
        if (userId == null) {
            throw new WebApplicationException("未登录", Response.Status.UNAUTHORIZED);
        }
        User user = User.find("id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
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
        // 获取文章的标签ID列表
        List<Long> tagIds = PostTag.find("post.id = ?1", post.id).stream()
                .map(pt -> ((PostTag) pt).tag.id)
                .toList();

        return new AdminPostItem(
                post.id,
                post.slug,
                post.title,
                statusCode(post.status),
                post.visibility,
                post.renderType == null ? PostRenderType.MARKDOWN.code() : post.renderType.code(),
                post.aiSummaryStatus == null ? 0 : post.aiSummaryStatus,
                post.version,
                post.user == null ? null : post.user.id,
                post.category == null ? null : post.category.id,
                tagIds,
                post.publishedAt,
                post.createdAt,
                post.updatedAt);
    }

    private AdminPostDetail toDetail(Post post) {
        // 获取文章的标签ID列表
        List<Long> tagIds = PostTag.find("post.id = ?1", post.id).stream()
                .map(pt -> ((PostTag) pt).tag.id)
                .toList();

        return new AdminPostDetail(
                post.id,
                post.slug,
                post.title,
                post.summary,
                post.aiSummary,
                post.currentRevision == null ? Map.of() : post.currentRevision.contentMarkdown,
                post.currentRevision == null ? null : post.currentRevision.contentBlocks,
                post.tutorialLevelDefs,
                statusCode(post.status),
                post.visibility,
                post.password,
                post.seoTitle,
                post.seoKeywords,
                post.seoDescription,
                post.renderType == null ? PostRenderType.MARKDOWN.code() : post.renderType.code(),
                post.currentRevision == null ? 0 : post.currentRevision.editorType,
                post.aiSummaryStatus == null ? 0 : post.aiSummaryStatus,
                post.currentRevision == null ? 0 : post.currentRevision.revisionNumber,
                post.version,
                post.user == null ? null : post.user.id,
                post.category == null ? null : post.category.id,
                tagIds,
                post.publishedAt,
                post.createdAt,
                post.updatedAt);
    }

    private PostStatus resolveStatus(Short status, PostStatus fallback) {
        if (status == null) {
            return fallback;
        }
        PostStatus resolved = PostStatus.fromCode(status);
        if (resolved == null) {
            throw conflict("未知文章状态: " + status);
        }
        return resolved;
    }

    private short statusCode(PostStatus status) {
        return status == null ? PostStatus.DRAFT.getCode() : status.getCode();
    }

    private <T> boolean updateField(T currentValue, T newValue, Consumer<T> setter) {
        if (newValue != null && !Objects.equals(currentValue, newValue)) {
            setter.accept(newValue);
            return true;
        }
        return false;
    }

    private boolean updatePostTags(Post post, List<Long> newTagIds) {
        List<Long> currentTagIds = PostTag.find("post.id = ?1", post.id).stream()
                .map(pt -> ((PostTag) pt).tag.id)
                .toList();

        Set<Long> currentSet = new HashSet<>(currentTagIds);
        Set<Long> newSet = new HashSet<>(newTagIds != null ? newTagIds : List.of());

        boolean changed = false;

        Set<Long> toRemove = new HashSet<>(currentSet);
        toRemove.removeAll(newSet);
        if (!toRemove.isEmpty()) {
            PostTag.delete("post.id = ?1 and tag.id in ?2", post.id, toRemove);
            changed = true;
        }

        Set<Long> toAdd = new HashSet<>(newSet);
        toAdd.removeAll(currentSet);
        for (Long tagId : toAdd) {
            Tag tag = Tag.findById(tagId);
            if (tag != null) {
                PostTag postTag = new PostTag();
                postTag.id = new PostTagId(post.id, tag.id);
                postTag.post = post;
                postTag.tag = tag;
                postTag.persist();
                changed = true;
            }
        }

        return changed;
    }

    private boolean updateBasicFields(Post post, PostUpdateRequest request) {
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

        changed |= updateField(post.summary, request.summary(), v -> post.summary = v);
        changed |= updateField(post.aiSummary, request.aiSummary(), v -> post.aiSummary = v);
        changed |= updateField(post.aiSummaryStatus, request.aiSummaryStatus(), v -> post.aiSummaryStatus = v);
        changed |= updateField(post.visibility, request.visibility(), v -> post.visibility = v);
        changed |= updateField(post.password, request.password(), v -> post.password = v);
        changed |= updateField(post.seoTitle, request.seoTitle(), v -> post.seoTitle = v);
        changed |= updateField(post.seoKeywords, request.seoKeywords(), v -> post.seoKeywords = v);
        changed |= updateField(post.seoDescription, request.seoDescription(), v -> post.seoDescription = v);
        changed |= updateField(post.tutorialLevelDefs, request.tutorialLevelDefs(), v -> post.tutorialLevelDefs = v);

        if (request.renderType() != null) {
            PostRenderType nextRenderType = requireRenderType(request.renderType());
            changed |= updateField(post.renderType, nextRenderType, v -> post.renderType = v);
        }

        if (request.status() != null) {
            PostStatus nextStatus = PostStatus.fromCode(request.status());
            if (nextStatus == null) {
                throw conflict("未知文章状态: " + request.status());
            }
            if (!Objects.equals(post.status, nextStatus)) {
                post.status = nextStatus;
                changed = true;
                if (nextStatus == PostStatus.PUBLISHED && post.publishedAt == null) {
                    post.publishedAt = OffsetDateTime.now();
                }
            }
        }

        return changed;
    }

    private boolean updateCategory(Post post, PostUpdateRequest request) {
        if (request.categoryId() != null) {
            Category newCategory = Category.findById(request.categoryId());
            if (newCategory != null && !newCategory.equals(post.category)) {
                post.category = newCategory;
                return true;
            }
        }
        return false;
    }

    private boolean updateContent(Post post, PostUpdateRequest request) {
        if (request.title() != null || request.contentMarkdown() != null || request.contentBlocks() != null || request.editorType() != null || request.tutorialLevelDefs() != null) {
            Map<String, String> nextTitle = request.title() == null ? post.title : request.title();
            Map<String, String> currentContent = post.currentRevision == null ? Map.of()
                    : post.currentRevision.contentMarkdown;
            Map<String, String> nextContent = request.contentMarkdown() == null ? currentContent
                    : request.contentMarkdown();
            Map<String, List<TutorialBlock>> currentBlocks = post.currentRevision == null ? null
                    : post.currentRevision.contentBlocks;
            Map<String, List<TutorialBlock>> nextBlocks = request.contentBlocks() == null ? currentBlocks
                    : request.contentBlocks();
            List<TutorialLevelDef> currentLevels = post.currentRevision == null ? null
                    : post.currentRevision.tutorialLevelDefs;
            List<TutorialLevelDef> nextLevels = request.tutorialLevelDefs() == null ? currentLevels
                    : request.tutorialLevelDefs();
            
            short currentEditorType = post.currentRevision == null ? 0 : post.currentRevision.editorType;
            short nextEditorType = request.editorType() == null ? currentEditorType : request.editorType();

            boolean revisionChanged = !Objects.equals(nextTitle, post.title)
                    || !Objects.equals(nextContent, currentContent)
                    || !Objects.equals(nextBlocks, currentBlocks)
                    || !Objects.equals(nextLevels, currentLevels)
                    || nextEditorType != currentEditorType;
            if (revisionChanged) {
                User operator = mustFindOperator();
                PostRevision nextRevision = new PostRevision();
                nextRevision.post = post;
                nextRevision.title = nextTitle;
                nextRevision.contentMarkdown = nextContent;
                nextRevision.contentBlocks = nextBlocks;
                nextRevision.tutorialLevelDefs = nextLevels;
                nextRevision.editorType = nextEditorType;
                nextRevision.revisionNumber = nextRevisionNumber(post.id);
                nextRevision.createdBy = operator;
                nextRevision.createdAt = OffsetDateTime.now();
                nextRevision.persist();

                post.currentRevision = nextRevision;
                mediaService.syncPostReferences(post, nextRevision.contentMarkdown, nextRevision.contentBlocks);
                post.title = nextRevision.title;
                return true;
            }
        }
        return false;
    }

    @GET
    @Path("/{id}/revisions")
    @Transactional
    @Operation(summary = "获取文章版本列表")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "404", description = "文章不存在")
    public List<PostRevisionItem> listRevisions(@PathParam("id") Long id) {
        Post post = mustFindPost(id);
        List<PostRevision> revisions = PostRevision.list(
                "post.id = ?1",
                Sort.by("revisionNumber").descending(),
                id
        );
        return revisions.stream().map(this::toRevisionItem).toList();
    }

    @GET
    @Path("/{id}/revisions/{revisionNumber}")
    @Transactional
    @Operation(summary = "获取文章特定版本详情")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "404", description = "文章或版本不存在")
    public AdminPostDetail getRevision(@PathParam("id") Long id, @PathParam("revisionNumber") int revisionNumber) {
        Post post = mustFindPost(id);
        PostRevision revision = PostRevision.find(
                "post.id = ?1 and revisionNumber = ?2",
                id,
                revisionNumber
        ).firstResult();
        if (revision == null) {
            throw new NotFoundException("版本不存在");
        }

        return new AdminPostDetail(
                post.id,
                post.slug,
                revision.title,
                post.summary,
                post.aiSummary,
                revision.contentMarkdown,
                revision.contentBlocks,
                revision.tutorialLevelDefs,
                statusCode(post.status),
                post.visibility,
                post.password,
                post.seoTitle,
                post.seoKeywords,
                post.seoDescription,
                post.renderType == null ? PostRenderType.MARKDOWN.code() : post.renderType.code(),
                revision.editorType,
                post.aiSummaryStatus == null ? 0 : post.aiSummaryStatus,
                revision.revisionNumber,
                post.version,
                post.user == null ? null : post.user.id,
                post.category == null ? null : post.category.id,
                PostTag.find("post.id = ?1", post.id).stream()
                        .map(pt -> ((PostTag) pt).tag.id)
                        .toList(),
                post.publishedAt,
                post.createdAt,
                post.updatedAt
        );
    }

    @POST
    @Path("/{id}/revisions/{revisionNumber}/activate")
    @Transactional
    @Operation(summary = "激活特定版本为当前版本")
    @APIResponse(responseCode = "200", description = "成功")
    @APIResponse(responseCode = "404", description = "文章或版本不存在")
    public AdminPostDetail activateRevision(@PathParam("id") Long id, @PathParam("revisionNumber") int revisionNumber) {
        Post post = mustFindPost(id);
        PostRevision revision = PostRevision.find(
                "post.id = ?1 and revisionNumber = ?2",
                id,
                revisionNumber
        ).firstResult();
        if (revision == null) {
            throw new NotFoundException("版本不存在");
        }

        User operator = mustFindOperator();
        OffsetDateTime now = OffsetDateTime.now();

        PostRevision newRevision = new PostRevision();
        newRevision.post = post;
        newRevision.title = revision.title;
        newRevision.contentMarkdown = revision.contentMarkdown;
        newRevision.contentBlocks = revision.contentBlocks;
        newRevision.tutorialLevelDefs = revision.tutorialLevelDefs;
        newRevision.editorType = revision.editorType;
        newRevision.revisionNumber = nextRevisionNumber(post.id);
        newRevision.createdBy = operator;
        newRevision.createdAt = now;
        newRevision.persist();

        post.currentRevision = newRevision;
        post.tutorialLevelDefs = newRevision.tutorialLevelDefs;
        post.title = newRevision.title;
        post.updatedAt = now;

        mediaService.syncPostReferences(post, newRevision.contentMarkdown, newRevision.contentBlocks);
        esSyncEvent.fire(new PostSyncedEvent(post.id));

        return toDetail(post);
    }


    private PostRevisionItem toRevisionItem(PostRevision revision) {
        return new PostRevisionItem(
                revision.id,
                revision.revisionNumber,
                revision.title,
                revision.editorType,
                revision.createdBy == null ? null : revision.createdBy.id,
                revision.createdBy == null ? "" : revision.createdBy.username,
                revision.createdAt
        );
    }
}
